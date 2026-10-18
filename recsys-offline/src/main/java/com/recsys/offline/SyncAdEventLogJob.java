package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import com.recsys.offline.adlog.AdEventSyncStore;
import com.recsys.offline.adlog.AdEventSyncProtocol.*;
import com.recsys.offline.persistence.OfflineSourceSessions;
import com.recsys.offline.persistence.mapper.primary.AdEventSyncMapper.Checkpoint;
import com.recsys.offline.persistence.mapper.source.AdEventCopySourceMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;
import static com.recsys.offline.adlog.AdEventSyncProtocol.*;

/**
 * 广告追加日志的有界核对作业：一轮上界只限制分页，不代表提交顺序；下一轮从零补晚提交低ID。
 * 每页效果与检查点同库原子，源读取在事务外；source-id和显式full恢复命令见C18_RUNBOOK。
 */
@Component
public class SyncAdEventLogJob implements OfflineJob {
    private static final String SOURCE_XML = "classpath*:mapper/offline/source/AdEventCopySourceMapper.xml";
    private final DataSource dataSource;
    private final AdEventSyncStore store;

    /** offline主源只负责目标，来源会话固定加载只读Mapper。 */
    public SyncAdEventLogJob(@Qualifier("sqlSessionTemplate") SqlSessionTemplate sessions) {
        dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
        store = new AdEventSyncStore(sessions);
    }

    /** 保留原注册名称，调用方需显式提供稳定源身份。 */
    @Override public String name() { return "sync-ad-event-log"; }

    /** 外层事务与坏参数在所有副作用前拒绝，不能伪造全任务回滚保证。 */
    @Override public void run(ApplicationArguments args) {
        Options options = options(args);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        long deadline = System.nanoTime() + Duration.ofSeconds(options.seconds()).toNanos();
        var source = buildSourceSessions(options.sourceDb()).getMapper(AdEventCopySourceMapper.class);
        store.install();
        Checkpoint original = store.checkpoint();
        // 首次来源与破坏性full先核验实际源。普通恢复的源IO纳入持久页预算，宕机不能无限免费重试。
        boolean inspectBeforeMutation = original == null || options.command() != null && options.command().action() == Action.REBUILD;
        if (inspectBeforeMutation && source.invalidIds()) throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        String fingerprint = inspectBeforeMutation ? AdEventSyncStore.fingerprint(source.identity()) : original.sourceFingerprint();
        store.prepare(options.sourceId(), fingerprint, options.command());
        Checkpoint claim = store.claim(UUID.randomUUID().toString());
        try {
            for (int page = 0; page < options.pages() && System.nanoTime() < deadline; page++) {
                claim = store.beginAttempt(claim);
                if (!claim.sourceFingerprint().equals(AdEventSyncStore.fingerprint(source.identity())) || source.invalidIds())
                    throw new PageFailure(Reason.SOURCE_CHANGED, null);
                if (claim.upperId() == null) claim = store.freeze(claim, source.upper());
                var rows = source.page(claim.cursorId(), claim.upperId(), PAGE_SIZE);
                claim = store.commitPage(claim, rows);
                afterPageCommitted(claim);
                if (State.COMPLETE.code().equals(claim.state())) break;
            }
            store.release(claim);
            Checkpoint result = store.checkpoint();
            LoggerFactory.getLogger(SyncAdEventLogJob.class).info(
                    "sync-ad-event-log epoch={} state={} cursor={} upper={} scanned={} copied={} continuationRequired={}",
                    result.epoch(), result.state(), result.cursorId(), result.upperId(), result.scanned(), result.copied(), !State.COMPLETE.code().equals(result.state()));
        } catch (RuntimeException failure) {
            Reason reason = failure instanceof PageFailure classified ? classified.reason() : Reason.DEPENDENCY;
            Long eventId = failure instanceof PageFailure classified ? classified.eventId() : null;
            // 持久失败不可达时保留租约/预算等待接管，不用二次异常覆盖原故障或宣称成功。
            try { store.fail(claim, reason, eventId); }
            catch (RuntimeException unavailable) { failure.addSuppressed(unavailable); }
            // JDBC约束错误可能携带整行原文；固定原因/冲突ID已落库，不能让CLI启动栈再次打印原异常。
            throw new PublicErrorException(reason.error());
        }
    }

    /** 仅用于独立故障进程验收，产品没有跳过检查点的配置开关。 */
    protected void afterPageCommitted(Checkpoint checkpoint) { }

    /** 同库和跨库共用同一分页SQL，避免快路径退回MAX水位。 */
    SqlSessionTemplate buildSourceSessions(String sourceDb) {
        if (sourceDb != null) return OfflineSourceSessions.createBounded(sourceDb, SOURCE_XML);
        try { return new SqlSessionTemplate(SpringSqlSessions.create(dataSource, SOURCE_XML)); }
        catch (Exception invalid) { throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT, invalid); }
    }

    private record Options(UUID sourceId, String sourceDb, int pages, int seconds, Command command) { }
    private static Options options(ApplicationArguments args) {
        UUID sourceId = uuid(JobArguments.text(args, "source-id", null));
        String sourceDb = JobArguments.text(args, "source-db", null);
        if (sourceDb != null && !sourceDb.matches("[A-Za-z0-9_][A-Za-z0-9_-]{0,62}")) throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        int pages = JobArguments.integer(args, "max-pages", MAX_PAGES), seconds = JobArguments.integer(args, "max-seconds", MAX_SECONDS);
        if (pages < 1 || pages > MAX_PAGES || seconds < 1 || seconds > MAX_SECONDS) throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        String rebuild = JobArguments.text(args, "rebuild-command-id", null), resume = JobArguments.text(args, "resume-command-id", null);
        boolean full = args.containsOption("full");
        if (full != (rebuild != null) || rebuild != null && resume != null) throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        if (full && !args.getOptionValues("full").isEmpty()) throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        Command command = rebuild != null || resume != null ? new Command(uuid(rebuild != null ? rebuild : resume),
                rebuild != null ? Action.REBUILD : Action.RESUME, JobArguments.text(args, "actor", null), JobArguments.text(args, "reason", null)) : null;
        if (command == null && (args.containsOption("actor") || args.containsOption("reason"))) throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        return new Options(sourceId, sourceDb, pages, seconds, command);
    }
    private static UUID uuid(String value) {
        try { UUID id = UUID.fromString(value); if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException(); return id; }
        catch (IllegalArgumentException | NullPointerException invalid) { throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT); }
    }
}
