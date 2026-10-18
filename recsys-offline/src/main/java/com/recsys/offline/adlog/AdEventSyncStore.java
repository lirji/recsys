package com.recsys.offline.adlog;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import com.recsys.offline.persistence.mapper.primary.AdEventLogMapper;
import com.recsys.offline.persistence.mapper.primary.AdEventSyncMapper;
import com.recsys.offline.persistence.mapper.primary.AdEventSyncMapper.*;
import com.recsys.offline.persistence.model.AdEventLogRow;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import static com.recsys.offline.adlog.AdEventSyncProtocol.*;

/** 日志目标本库事务Owner：一页效果与检查点共同提交，失败预算先独立保存。 */
public class AdEventSyncStore {
    public static final String MIGRATION = "db/ad-event-sync/V001__ad_event_sync.sql";
    private final AdEventSyncMapper mapper;
    private final AdEventLogMapper target;
    private final DataSource dataSource;
    private final TransactionTemplate transaction;

    /** 明确绑定offline主源，不能借另一个默认事务管理器提交检查点。 */
    public AdEventSyncStore(SqlSessionTemplate sessions) {
        mapper = sessions.getMapper(AdEventSyncMapper.class); target = sessions.getMapper(AdEventLogMapper.class);
        dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); transaction.setTimeout(5);
    }

    /** 仅明确执行作业时安装；同一锁保护两执行器首次并发建表。 */
    public void install() {
        tx(() -> {
            if (mapper.installed() == null) {
                new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/ad-event-log.sql")).execute(dataSource);
                mapper.prepareInstall(migrationDigest());
                new ResourceDatabasePopulator(new ClassPathResource(MIGRATION)).execute(dataSource);
            }
            if (!migrationDigest().equals(mapper.schemaDigest())) throw new PublicErrorException(ErrorCode.JOB_STATUS_INVALID);
            return null;
        });
    }

    /** 已绑定数据集不可由full或重启换成另一个来源。 */
    public void prepare(UUID source, String fingerprint, Command command) {
        tx(() -> {
            mapper.initialize(source.toString(), fingerprint);
            Checkpoint current = mapper.checkpoint();
            if (!current.sourceId().equals(source.toString()) || !current.sourceFingerprint().equals(fingerprint))
                throw new PublicErrorException(ErrorCode.CONFLICT);
            if (command != null) execute(command);
            return null;
        });
    }

    /** 查询恢复状态不隐式领取或重置预算。 */
    public Checkpoint checkpoint() { return mapper.checkpoint(); }

    /** 末次退出先持久转PAUSED，再向调用方报告；不能被异常回滚掉暂停标记。 */
    public Checkpoint claim(String worker) {
        Checkpoint claimed = tx(() -> { mapper.pauseExhausted(); return mapper.claim(worker) == 1 ? mapper.checkpoint() : null; });
        if (claimed == null) throw new PublicErrorException(State.PAUSED.code().equals(mapper.checkpoint().state())
                ? ErrorCode.JOB_EXECUTION_FAILED : ErrorCode.JOB_ALREADY_RUNNING);
        return claimed;
    }

    /** 原执行器在源读取前先消费预算，跨进程退出不能免费重试。 */
    public Checkpoint beginAttempt(Checkpoint claim) {
        return tx(() -> { one(mapper.beginAttempt(claim.worker(), claim.fence())); return mapper.checkpoint(); });
    }

    /** 冻结本轮有限上界，后续新高ID留到下一轮，不挤占旧轮。 */
    public Checkpoint freeze(Checkpoint claim, long upper) {
        return tx(() -> { fence(claim); if (upper < 0) throw new PageFailure(Reason.SOURCE_CHANGED, null);
            one(mapper.freezeUpper(upper)); return mapper.checkpoint(); });
    }

    /** 重放页的16列必须与已接受原记录一致；任何冲突回滚本页新增行。 */
    public Checkpoint commitPage(Checkpoint claim, List<AdEventLogRow> rows) {
        if (rows.size() > PAGE_SIZE || claim.upperId() == null) throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        long last = claim.cursorId();
        for (AdEventLogRow row : rows) {
            if (row.id() <= last || row.id() > claim.upperId()) throw new PageFailure(Reason.SOURCE_CHANGED, row.id());
            last = row.id();
        }
        long cursor = rows.isEmpty() ? claim.upperId() : last;
        return tx(() -> {
            fence(claim);
            Checkpoint current = mapper.checkpoint();
            if (current.epoch() != claim.epoch() || current.cursorId() != claim.cursorId() || !Objects.equals(current.upperId(), claim.upperId()))
                throw new PublicErrorException(ErrorCode.CONFLICT);
            int inserted = 0;
            if (!rows.isEmpty()) {
                inserted = target.insertRows(rows);
                Long conflict = mapper.conflictingId(rows);
                if (conflict != null) throw new PageFailure(Reason.CONTENT_CONFLICT, conflict);
            }
            // 源IO不在事务内，目标效果提交前重新核验租约，以免耗时SQL跨过失效点。
            fence(claim);
            one(mapper.advance(cursor, rows.size(), inserted, cursor == claim.upperId()));
            return mapper.checkpoint();
        });
    }

    /** 固定诊断持久化，不泄露源payload；旧fence不能暂停新实例。 */
    public void fail(Checkpoint claim, Reason reason, Long eventId) {
        tx(() -> { one(mapper.fail(claim.worker(), claim.fence(), reason.code(), eventId, reason != Reason.DEPENDENCY)); return null; });
    }

    /** 正常调用预算到期仅让出，下一次从同一游标续办。 */
    public void release(Checkpoint claim) { tx(() -> { mapper.release(claim.worker(), claim.fence()); return null; }); }

    private void execute(Command command) {
        Audit previous = mapper.audit(command.id().toString());
        if (previous != null) {
            if (!previous.action().equals(command.action().code()) || !previous.actor().equals(command.actor()) || !previous.reason().equals(command.reason()))
                throw new PublicErrorException(ErrorCode.CONFLICT);
            return;
        }
        if (command.action() == Action.REBUILD) {
            one(mapper.rebuild());
            // 只有带审计的显式full才清空；清空与恢复点/命令记录一起提交，重试不能再次清空。
            target.truncate();
        } else one(mapper.resume());
        one(mapper.addAudit(new Audit(command.id().toString(), command.action().code(), command.actor(), command.reason(), mapper.checkpoint().epoch())));
    }
    private void fence(Checkpoint claim) { one(mapper.checkFence(claim.worker(), claim.fence())); }
    private static void one(int affected) { if (affected != 1) throw new PublicErrorException(ErrorCode.CONFLICT); }
    private <T> T tx(Supplier<T> work) { return transaction.execute(status -> { mapper.budget(); mapper.lock(); return work.get(); }); }
    /** 源描述摘要不包含凭据，日志也不打印原端点。 */
    public static String fingerprint(String identity) { return digest(identity.getBytes(StandardCharsets.UTF_8)); }
    private static String migrationDigest() {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) { return digest(input.readAllBytes()); }
        catch (java.io.IOException failure) { throw new PublicErrorException(ErrorCode.JOB_STATUS_INVALID, failure); }
    }
    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new PublicErrorException(ErrorCode.INTERNAL_ERROR, failure); }
    }
}
