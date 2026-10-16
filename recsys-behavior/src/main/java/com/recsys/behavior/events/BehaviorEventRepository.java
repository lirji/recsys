package com.recsys.behavior.events;

import com.recsys.behavior.persistence.mapper.primary.BehaviorEventMapper;
import com.recsys.behavior.persistence.mapper.primary.BehaviorEventMapper.*;
import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.common.behavior.LegacyBehaviorAccepted;
import com.recsys.common.behavior.LegacyBehaviorAccepted.Origin;
import com.recsys.platform.behavior.LegacyBehaviorCodec;
import com.recsys.common.dto.BehaviorEvent;
import com.recsys.behavior.persistence.mapper.primary.BehaviorMapper;
import com.recsys.behavior.persistence.model.BehaviorWrite;
import java.sql.Timestamp;
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
public class BehaviorEventRepository {
    public static final String MIGRATION = "db/behavior-events/V001__behavior_event_delivery.sql";
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
        INVALID_SOURCE("INVALID_SOURCE"), INVALID_EVENT("INVALID_EVENT"), BROKER_UNAVAILABLE("BROKER_UNAVAILABLE"),
        SEND_UNKNOWN("SEND_UNKNOWN"), ATTEMPTS_EXHAUSTED("ATTEMPTS_EXHAUSTED");
        private final String code;
        Error(String code) { this.code = code; }
        /** 稳定错误分类供 SQL 约束与观测共同使用。 */
        public String code() { return code; }
    }

    private final BehaviorEventMapper mapper;
    private final TransactionTemplate transactions;
    private final BehaviorMapper behavior;
    private final LegacyBehaviorCodec codec;
    private final javax.sql.DataSource dataSource;
    /** 是否新业务行决定指标；重复修复也成功，但不能重复计业务。 */
    public enum Acceptance { INSERTED, DUPLICATE, COLLISION }
    /** 两个逻辑流独立排空，不接收任意目标。 */
    public enum Destination {
        LIVE(LegacyBehaviorAccepted.LIVE_TOPIC), HISTORY(LegacyBehaviorAccepted.HISTORY_TOPIC);
        private final String topic;
        Destination(String topic) { this.topic=topic; }
        public String topic() { return topic; }
    }

    /** 验证实际连接资源，防止事务与 Mapper 写入不同库。 */
    public BehaviorEventRepository(SqlSessionTemplate sessions, DataSourceTransactionManager manager) {
        this(sessions, manager, new LegacyBehaviorCodec());
    }

    /** codec显式注入供序列化故障验证；任何非数据异常必须回滚整个接受事务。 */
    public BehaviorEventRepository(SqlSessionTemplate sessions, DataSourceTransactionManager manager, LegacyBehaviorCodec codec) {
        if (sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource() != manager.getDataSource()) {
            throw new IllegalArgumentException("行为发送事务与 Mapper 必须共用同一数据源");
        }
        mapper = sessions.getMapper(BehaviorEventMapper.class);
        dataSource = manager.getDataSource();
        behavior = sessions.getMapper(BehaviorMapper.class);
        this.codec = Objects.requireNonNull(codec);
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(5);
    }

    /** 只核对显式迁移，不触发任何共享库升级。 */
    public void verifySchema() {
        transaction(() -> {
            if (!mapper.verifySourceSchema() || mapper.sourceId()==null
                    || !migrationSha256().equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes(1)))) {
                throw new IllegalStateException("行为事件迁移缺失或摘要漂移");
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
            throw new IllegalStateException("行为事件迁移不可读", ex);
        }
    }

    /** 归因在调用前完成；每条请求独立提交，外层batch事务不能合并本事务。 */
    public Acceptance accept(BehaviorEvent event) {
        Objects.requireNonNull(event);
        return transaction(() -> {
            Long id = behavior.insertReturning(new BehaviorWrite(event.userId(),event.itemId(),
                    event.action()==null ? null : event.action().name(),event.value(),event.scene(),event.bucket(),
                    event.exposureId(),new Timestamp(event.ts())));
            if (id!=null) {
                capture(id, Origin.BEHAVIOR_POST, "ONLINE_ACCEPT", event.ts());
                return Acceptance.INSERTED;
            }
            // 下一条语句使用新快照，不能在INSERT的CTE里读取并发赢家。
            Click original = mapper.lockClick(event.exposureId());
            if (original==null) throw new IllegalStateException("行为冲突源行已不可确认");
            if (!Objects.equals(original.userId(),event.userId()) || !Objects.equals(original.itemId(),event.itemId())) {
                return Acceptance.COLLISION;
            }
            if (mapper.existing(original.behaviorId())==null) {
                capture(original.behaviorId(),Origin.LEGACY_ROW,"CLICK_DUPLICATE_REPAIR",null);
            }
            return Acceptance.DUPLICATE;
        });
    }

    /** 历史清单复用原捕获实现；必须加入同源现有事务，不能另开事务提前提交Outbox。 */
    void captureHistoryInTransaction(long id) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("历史捕获必须处于同源事务");
        }
        capture(id, Origin.LEGACY_ROW, "HISTORY_COMMAND", null);
    }

    private void capture(long id, Origin origin, String reason, Long normalizedTs) {
        Source source = mapper.source(id);
        if (source==null) throw new IllegalStateException("行为源行或源身份缺失");
        String payload=null, key=null;
        try {
            payload=codec.capture(source.sourceId().toString(),source.snapshot(),source.capturedAt(),origin,normalizedTs);
            key=codec.partitionKey(payload);
        } catch (LegacyBehaviorCodec.InvalidEvent ex) {
            // 已有HTTP政策允许的异常值保留源行和DEAD义务，绝不偷偷丢弃或补默认值。
            payload=null;
        }
        String destination=origin==Origin.BEHAVIOR_POST ? Destination.LIVE.topic() : Destination.HISTORY.topic();
        if (mapper.insertEvent(id,origin.name(),destination,reason,source.capturedAt(),payload,key)!=1) {
            throw new IllegalStateException("行为发送义务未原子保存");
        }
    }

    /** 每次只领取一条；返回时数据库事务已经提交。 */
    public Optional<Pending> claim(Destination destination) {
        return transaction(() -> mapper.claim(MAX_ATTEMPTS, LEASE_SECONDS, destination.topic()).stream().findFirst());
    }

    /** 过期或被接管的 worker 无权宣布已发布。 */
    public boolean published(Pending event) {
        return transaction(() -> mapper.published(event.eventId(), event.fence()) == 1);
    }

    /** 有界退避；坏事件立即隔离，其余失败最多二十次领取。 */
    public boolean failed(Pending event, long delayMs, Error error) {
        if (delayMs < 1000 || delayMs > 60000 || error == null || (error == Error.ATTEMPTS_EXHAUSTED || error == Error.INVALID_SOURCE)) {
            throw new IllegalArgumentException("行为事件重试参数非法");
        }
        return transaction(() -> mapper.failed(event.eventId(), event.fence(), delayMs, error.code(), MAX_ATTEMPTS) == 1);
    }

    /** 最后一次领取后崩溃也必须有界进入可观测终态。 */
    public int exhaust(Destination destination) { return transaction(() -> mapper.exhaust(MAX_ATTEMPTS, EXHAUST_LIMIT, destination.topic())); }

    /** 内部受控恢复命令，调用方负责可信 actor；原命令重试始终返回原审计。 */
    public Replay replay(UUID replayId, String eventId, String actor, String reason) {
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
            if (event == null || !State.DEAD.code().equals(event.status()) || Error.INVALID_SOURCE.code().equals(event.error())) throw new IllegalStateException("仅 DEAD 行为事件可重放");
            int inserted = mapper.insertReplay(replayId, eventId, actor, reason, event.attempts(), event.fence());
            if (inserted == 0) return original(Objects.requireNonNull(mapper.replay(replayId)), eventId, actor, reason);
            if (mapper.reopen(eventId, event.fence()) != 1) throw new IllegalStateException("行为重放未生效");
            return Objects.requireNonNull(mapper.replay(replayId));
        });
    }

    /** 观测失败向上传递，不以空积压掩盖数据库不可用。 */
    public Backlog backlog(Destination destination) { return transaction(() -> mapper.backlog(destination.topic())); }

    private Replay original(Replay replay, String eventId, String actor, String reason) {
        if (!replay.eventId().equals(eventId) || !replay.actor().equals(actor) || !replay.reason().equals(reason)) {
            throw new IllegalArgumentException("行为重放命令内容冲突");
        }
        return replay;
    }

    private <T> T transaction(Supplier<T> work) {
        try {
            return transactions.execute(status -> {
                mapper.boundLocks();
                mapper.boundStatements();
                return work.get();
            });
        } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException ex) {
            // PG约束异常可能携带完整失败行；统一错误边界仍给500，但普通日志不得留下源快照。
            throw new IllegalStateException("行为持久化操作失败，结果可能未知");
        }
    }
}
