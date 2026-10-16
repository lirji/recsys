package com.recsys.behavior.exposure;

import com.recsys.behavior.persistence.mapper.primary.NaturalOutboxMapper;
import com.recsys.behavior.persistence.mapper.primary.NaturalOutboxMapper.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** behavior 自有发送状态；每个操作独立短事务，绝不持锁等待 Kafka。 */
public class NaturalOutboxRepository {
    public static final String MIGRATION = "db/natural-exposure/V002__natural_event_delivery.sql";
    public static final int MAX_ATTEMPTS = 20;
    public static final int LEASE_SECONDS = 30;
    public static final int EXHAUST_LIMIT = 10;
    /** 闭合生命周期使用显式持久化码，不依赖枚举序号。 */
    public enum State {
        READY("READY"), SENDING("SENDING"), PUBLISHED("PUBLISHED"), DEAD("DEAD");
        private final String code;
        State(String code) { this.code = code; }
        /** 数据库存储值与 Java 枚举序号无关。 */
        public String code() { return code; }
    }
    /** 错误值持久化且不含第三方异常文本。 */
    public enum Error {
        INVALID_EVENT("INVALID_EVENT"), BROKER_UNAVAILABLE("BROKER_UNAVAILABLE"),
        SEND_UNKNOWN("SEND_UNKNOWN"), ATTEMPTS_EXHAUSTED("ATTEMPTS_EXHAUSTED");
        private final String code;
        Error(String code) { this.code = code; }
        /** 稳定错误分类供 SQL 约束与观测共同使用。 */
        public String code() { return code; }
    }

    private final NaturalOutboxMapper mapper;
    private final TransactionTemplate transactions;

    /** 验证实际连接资源，防止事务与 Mapper 写入不同库。 */
    public NaturalOutboxRepository(SqlSessionTemplate sessions, DataSourceTransactionManager manager) {
        if (sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource() != manager.getDataSource()) {
            throw new IllegalArgumentException("自然发送事务与 Mapper 必须共用同一数据源");
        }
        mapper = sessions.getMapper(NaturalOutboxMapper.class);
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(5);
    }

    /** 只核对显式迁移，不触发任何共享库升级。 */
    public void verifySchema() {
        transaction(() -> {
            if (!NaturalReceiptRepository.migrationSha256().equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes(1)))
                    || !migrationSha256().equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes(2)))) {
                throw new IllegalStateException("自然事件迁移缺失或摘要漂移");
            }
            mapper.verifyOutbox();
            mapper.verifyReplay();
            return true;
        });
    }

    /** 迁移工具及校验共用实际打包脚本摘要。 */
    public static String migrationSha256() {
        try (var in = new ClassPathResource(MIGRATION).getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("自然事件迁移不可读", ex);
        }
    }

    /** 每次只领取一条；返回时数据库事务已经提交。 */
    public Optional<Pending> claim() {
        return transaction(() -> mapper.claim(MAX_ATTEMPTS, LEASE_SECONDS).stream().findFirst());
    }

    /** 过期或被接管的 worker 无权宣布已发布。 */
    public boolean published(Pending event) {
        return transaction(() -> mapper.published(event.eventId(), event.fence()) == 1);
    }

    /** 有界退避；坏事件立即隔离，其余失败最多二十次领取。 */
    public boolean failed(Pending event, long delayMs, Error error) {
        if (delayMs < 1000 || delayMs > 60000 || error == null || error == Error.ATTEMPTS_EXHAUSTED) {
            throw new IllegalArgumentException("自然事件重试参数非法");
        }
        return transaction(() -> mapper.failed(event.eventId(), event.fence(), delayMs, error.code(), MAX_ATTEMPTS) == 1);
    }

    /** 最后一次领取后崩溃也必须有界进入可观测终态。 */
    public int exhaust() { return transaction(() -> mapper.exhaust(MAX_ATTEMPTS, EXHAUST_LIMIT)); }

    /** 内部受控恢复命令，调用方负责可信 actor；原命令重试始终返回原审计。 */
    public Replay replay(UUID replayId, UUID eventId, String actor, String reason) {
        Objects.requireNonNull(replayId);
        Objects.requireNonNull(eventId);
        if (actor == null || actor.isBlank() || actor.length() > 200
                || reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new IllegalArgumentException("重放需要受控操作者和原因");
        }
        return transaction(() -> {
            Replay original = mapper.replay(replayId);
            if (original != null) return original(original, eventId, actor, reason);
            Locked event = mapper.lockEvent(eventId);
            // 等待事件锁期间同一命令可能已经提交，先查原审计再判断当前状态。
            original = mapper.replay(replayId);
            if (original != null) return original(original, eventId, actor, reason);
            if (event == null || !State.DEAD.code().equals(event.status())) throw new IllegalStateException("仅 DEAD 自然事件可重放");
            int inserted = mapper.insertReplay(replayId, eventId, actor, reason, event.attempts(), event.fence());
            if (inserted == 0) return original(Objects.requireNonNull(mapper.replay(replayId)), eventId, actor, reason);
            if (mapper.reopen(eventId, event.fence()) != 1) throw new IllegalStateException("自然重放未生效");
            return Objects.requireNonNull(mapper.replay(replayId));
        });
    }

    /** 观测失败向上传递，不以空积压掩盖数据库不可用。 */
    public Backlog backlog() { return transaction(mapper::backlog); }

    private Replay original(Replay replay, UUID eventId, String actor, String reason) {
        if (!replay.eventId().equals(eventId) || !replay.actor().equals(actor) || !replay.reason().equals(reason)) {
            throw new IllegalArgumentException("自然重放命令内容冲突");
        }
        return replay;
    }

    private <T> T transaction(Supplier<T> work) {
        return transactions.execute(status -> {
            mapper.boundLocks();
            mapper.boundStatements();
            return work.get();
        });
    }
}
