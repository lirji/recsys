package com.recsys.adserving.catalog.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.AdCatalogEvent;
import com.recsys.common.ad.CatalogEventCodec;
import com.recsys.common.ad.VersionedAdCatalogEvent;
import com.recsys.adserving.persistence.mapper.addb.AdServableMapper;
import com.recsys.adserving.persistence.mapper.addb.CatalogProjectionMapper;
import com.recsys.adserving.persistence.mapper.addb.CatalogProjectionMapper.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import static com.recsys.adserving.catalog.projection.CatalogProjectionProtocol.*;
import static com.recsys.adserving.catalog.projection.CatalogProjectionProtocol.Code.*;

/** 本库事务 Owner；记录重试预算与应用事务分离，效果、Inbox、任务和恢复位点必须一起提交。 */
public class CatalogProjectionStore {
    public static final String MIGRATION = "db/catalog-projection/V001__catalog_projection.sql";
    private final CatalogProjectionMapper mapper;
    private final AdServableMapper catalog;
    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    /** 明确使用 adDb 同一个 DataSource，不借默认事务管理器混到主数据源。 */
    public CatalogProjectionStore(SqlSessionTemplate sessions, ObjectMapper json) {
        mapper = sessions.getMapper(CatalogProjectionMapper.class);
        catalog = sessions.getMapper(AdServableMapper.class);
        dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
        this.json = json;
    }

    /** 只供受控安装器显式调用；默认装配绝不运行 DDL。 */
    public void install() {
        tx(() -> {
            mapper.controlLock();
            if (mapper.installed() == null) {
                mapper.prepareInstall(migrationDigest());
                var installer = new ResourceDatabasePopulator(new ClassPathResource(MIGRATION));
                // PostgreSQL函数体含分号，整份资源交给驱动，不能按普通SQL分号截断美元引号。
                installer.setSeparator(org.springframework.jdbc.datasource.init.ScriptUtils.EOF_STATEMENT_SEPARATOR);
                installer.execute(dataSource);
            }
            verifySchema();
            return null;
        });
    }

    /** 已发布脚本不允许被修改后继续使用旧迁移记录。 */
    public void verifySchema() {
        if (mapper.installed() == null || !migrationDigest().equals(mapper.schemaDigest())) throw new Failure(SCHEMA_MISSING);
        mapper.positions();
    }

    /** 初始化清单在任何消费前冻结；不自动采用非空旧副本。 */
    public void initialize(UUID command, Control control, List<Position> positions, String baseline,
                           String actor, String reason) {
        CatalogProjectionProtocol.audit(command, actor, reason);
        if (control.sourceId() == null || control.clusterId() == null || control.topicId() == null
                || control.topicName() == null || control.groupId() == null || positions.isEmpty()
                || baseline == null || baseline.isBlank() || baseline.length() > 1000) throw new Failure(INVALID_COMMAND);
        UUID.fromString(control.sourceId());
        String target = write(List.of(control.sourceId(), control.clusterId(), control.topicId(), control.topicName(),
                control.groupId(), positions, baseline));
        tx(() -> {
            mapper.controlLock();
            if (previous(command, Kind.INITIALIZE.code(), target, actor, reason) != null) return null;
            if (mapper.control() != null) throw new Failure(SOURCE_CONFLICT);
            mapper.freezeLegacy();
            if (mapper.originalRows() != 0) throw new Failure(TARGET_NOT_EMPTY);
            requireOne(mapper.initialize(control));
            for (Position position : positions) {
                if (position.startOffset() != position.nextOffset()) throw new Failure(INVALID_COMMAND);
                requireOne(mapper.addPosition(position));
            }
            mapper.addAudit(new Audit(command.toString(), Kind.INITIALIZE.code(), target, actor, reason, null));
            return null;
        });
    }

    /** 控制状态和分区位置为内部运维只读结果。 */
    public Control control() { return mapper.control(); }
    /** 返回稳定分区顺序，启动时据此核验保留范围。 */
    public List<Position> positions() { return mapper.positions(); }
    /** 独占执行租约过期后才可接管，暂停不能靠重启清除。 */
    public Control claimConsumer(String worker) {
        return tx(() -> { mapper.controlLock(); requireOne(mapper.claimConsumer(worker)); return mapper.control(); });
    }
    /** 续租必须仍持有有效 fence，不允许旧实例把新执行器踢走。 */
    public void renew(String worker, long fence) { tx(() -> { requireOne(mapper.renew(worker, fence)); return null; }); }
    /** 故障持久停止；PG 不可达时由调用方停线程，不能假称标记成功。 */
    public void pause(String worker, long fence, Code code) { tx(() -> { mapper.pauseConsumer(worker, fence, code.code()); return null; }); }
    /** 正常退出保留原预算和位置，允许下一实例按新 fence 接管。 */
    public void release(String worker, long fence) { tx(() -> { mapper.releaseConsumer(worker, fence); return null; }); }

    /** 新恢复轮保留原投递，审计 ID 重试不会再次清预算。 */
    public void resume(UUID command, String actor, String reason) {
        CatalogProjectionProtocol.audit(command, actor, reason);
        tx(() -> {
            mapper.controlLock();
            if (previous(command, Kind.RESUME_CONSUMER.code(), "catalog", actor, reason) != null) return null;
            if (mapper.resumeConsumer() != 1) throw new Failure(NOT_ELIGIBLE);
            mapper.resetAttempts();
            mapper.addAudit(new Audit(command.toString(), Kind.RESUME_CONSUMER.code(), "catalog", actor, reason, null));
            return null;
        });
    }

    /** 返回后才能确认 Kafka；原始投递的预算先提交，崩溃不能免费无限重试。 */
    public State accept(ConsumerRecord<byte[], byte[]> record, String worker, long fence) {
        if (record.value() != null && record.value().length > MAX_WIRE_BYTES
                || record.key() != null && record.key().length > 1024) throw new Failure(INVALID_EVENT);
        String wireDigest = wireDigest(record.key(), record.value());
        Receipt receipt = tx(() -> {
            mapper.controlLock(); checkFence(worker, fence);
            if (!mapper.control().topicName().equals(record.topic())) throw new Failure(SOURCE_CONFLICT);
            Position position = position(record.partition());
            Receipt old = mapper.receipt(record.partition(), record.offset());
            if (old != null && !old.digest().equals(wireDigest)) throw new Failure(PAYLOAD_CONFLICT);
            if (record.offset() < position.nextOffset()) {
                if (old == null || State.READY.code().equals(old.state())) throw new Failure(OFFSET_GAP);
                return old;
            }
            if (record.offset() != position.nextOffset()) throw new Failure(OFFSET_GAP);
            mapper.capture(new Receipt(record.partition(), record.offset(), record.key(), record.value(), wireDigest, State.READY.code(), 0, null));
            if (mapper.attempt(record.partition(), record.offset()) != 1) throw new Failure(ATTEMPTS_EXHAUSTED);
            return mapper.receipt(record.partition(), record.offset());
        });
        if (!State.READY.code().equals(receipt.state())) return State.fromCode(receipt.state());
        return tx(() -> {
            mapper.controlLock(); checkFence(worker, fence);
            // 控制行技术锁使同一租约的并发误调用也不能重复推进位点。
            Receipt current = mapper.receipt(record.partition(), record.offset());
            if (!State.READY.code().equals(current.state())) return State.fromCode(current.state());
            State outcome = applyReceipt(record);
            requireOne(mapper.advance(record.partition(), record.offset()));
            checkFence(worker, fence);
            return outcome;
        });
    }

    /** 隔离重检只读原字节；不改主消费位置，不允许替换事件内容。每个命令至多执行一次。 */
    public void recheck(UUID command, int partition, long offset, String actor, String reason) {
        CatalogProjectionProtocol.audit(command, actor, reason);
        String target = partition + ":" + offset;
        tx(() -> {
            mapper.controlLock();
            if (previous(command, Kind.RECHECK_RECORD.code(), target, actor, reason) != null) return null;
            Receipt receipt = mapper.receipt(partition, offset);
            if (receipt == null) throw new Failure(NOT_FOUND);
            if (!State.QUARANTINED.code().equals(receipt.state()) || offset >= position(partition).nextOffset())
                throw new Failure(NOT_ELIGIBLE);
            if (!receipt.digest().equals(wireDigest(receipt.recordKey(), receipt.payload()))) throw new Failure(PAYLOAD_CONFLICT);
            requireOne(mapper.reopen(partition, offset));
            applyReceipt(new ConsumerRecord<>(mapper.control().topicName(), partition, offset, receipt.recordKey(), receipt.payload()));
            mapper.addAudit(new Audit(command.toString(), Kind.RECHECK_RECORD.code(), target, actor, reason, null));
            return null;
        });
    }

    /** 主消费与运维重检共用投影规则；调用方已持有控制锁及同一事务。 */
    private State applyReceipt(ConsumerRecord<byte[], byte[]> record) {
            State outcome;
            Code quarantine = null;
            VersionedAdCatalogEvent event;
            try {
                event = decode(record.value());
                CatalogEventCodec.verify(event, record.key(), record.value());
                if (!mapper.control().sourceId().equals(event.sourceId().toString())) throw new Failure(SOURCE_CONFLICT);
            } catch (Failure invalid) {
                event = null; quarantine = invalid.code();
            } catch (IllegalArgumentException invalid) {
                event = null; quarantine = INVALID_EVENT;
            }
            if (event == null) outcome = State.QUARANTINED;
            else {
                mapper.adLock(event.adId());
                String digest = CatalogEventCodec.digest(record.value());
                String known = mapper.inboxDigest(event.eventId());
                Projection old = mapper.projection(event.adId());
                if (known != null && !known.equals(digest)
                        || old != null && old.aggregateVersion() == event.aggregateVersion() && !old.digest().equals(digest)) {
                    outcome = State.QUARANTINED; quarantine = PAYLOAD_CONFLICT;
                } else if (known != null) outcome = State.STALE;
                else if (old != null && old.aggregateVersion() >= event.aggregateVersion()) {
                    outcome = State.STALE;
                    mapper.inbox(event.eventId(), event.adId(), event.aggregateVersion(), digest, outcome.code());
                } else if (old != null && old.deleted() && event.lifecycle() != VersionedAdCatalogEvent.Lifecycle.DELETED) {
                    outcome = State.QUARANTINED; quarantine = SOURCE_CONFLICT;
                } else {
                    outcome = State.APPLIED;
                    // 不可服务时向量退出召回，但同item已知原向量仍随版本状态保留，恢复UNKNOWN不丢失事实。
                    byte[] knownEmbedding = event.lifecycle() == VersionedAdCatalogEvent.Lifecycle.DELETED ? null
                            : event.embedding().state() == VersionedAdCatalogEvent.EmbeddingState.PRESENT ? record.value()
                            : old != null && Objects.equals(old.itemId(), event.ad().itemId()) ? old.embeddingPayload() : null;
                    applyCatalog(event, knownEmbedding);
                    Projection projected = new Projection(event.adId(), event.sourceId().toString(), event.aggregateVersion(), digest,
                            record.value(), knownEmbedding, event.ad() == null ? null : event.ad().itemId(), event.lifecycle() == VersionedAdCatalogEvent.Lifecycle.DELETED);
                    requireOne(mapper.project(projected));
                    mapper.inbox(event.eventId(), event.adId(), event.aggregateVersion(), digest, outcome.code());
                    mapper.addTask(event.adId(), event.aggregateVersion(), old == null ? null : old.payload(), record.value());
                }
            }
            requireOne(mapper.receiptDone(record.partition(), record.offset(), outcome.code(), quarantine == null ? null : quarantine.code()));
            return outcome;
    }

    private void applyCatalog(VersionedAdCatalogEvent event, byte[] knownEmbedding) {
        mapper.projectionWriter();
        if (!event.servable()) { catalog.delete(event.adId()); catalog.deleteEmbedding(event.adId()); return; }
        AdCatalogEvent view = view(event);
        requireOne(catalog.upsert(view, view.bidwords().stream().mapToDouble(AdCatalogEvent.Bidword::bid).max().orElse(0),
                write(view.bidwords()), write(view.creatives())));
        if (knownEmbedding != null) {
            var embedding = decode(knownEmbedding).embedding();
            requireOne(mapper.upsertEmbedding(event.adId(), write(embedding.vector()), embedding.model()));
        } else catalog.deleteEmbedding(event.adId());
    }

    /** 旧读模型只接收已批准创意；暂停/未知审核绝不自动成为 approved。 */
    public static AdCatalogEvent view(VersionedAdCatalogEvent event) {
        if (!event.servable()) return AdCatalogEvent.tombstone(event.adId(), event.capturedAt().toEpochMilli());
        var ad = event.ad();
        return new AdCatalogEvent(event.adId(), true, event.advertiserId(), ad.itemId(), ad.title(), ad.landingUrl(),
                ad.qualityScore(), ad.status(), ad.reviewStatus(), ad.optimizationType(), ad.targetCpa(), ad.audienceId(),
                event.bidwords().stream().map(w -> new AdCatalogEvent.Bidword(w.id(), w.keyword(), w.matchType(), w.bid())).toList(),
                event.creatives().stream().filter(c -> CatalogEventCodec.servable(event.advertiserStatus(), c.status(), c.reviewStatus()))
                        .map(c -> new AdCatalogEvent.Creative(c.creativeId(), c.title(), c.landingUrl(), c.status())).toList(),
                null, event.capturedAt().toEpochMilli());
    }

    /** 原字节解码失败统一分类，异常不携带 JSON 或标题。 */
    public VersionedAdCatalogEvent decode(byte[] payload) {
        try { return json.readValue(payload, VersionedAdCatalogEvent.class); }
        catch (Exception invalid) { throw new Failure(INVALID_EVENT); }
    }
    /** 领取最早未决任务，末次执行器退出也会在下次扫描收敛为 PAUSED。 */
    public Task claimTask() { return tx(() -> { mapper.expireTasks(); return mapper.claimTask(); }); }
    /** 有效原租约才能提交任务完成，Redis 成功不代表 PG 确认已成功。 */
    public void finish(Task task) { tx(() -> { requireOne(mapper.finishTask(task.taskId(), task.fence())); return null; }); }
    /** 失败持久退避；旧执行器不能修改后续恢复轮。 */
    public void fail(Task task, Code code) { tx(() -> { requireOne(mapper.failTask(task.taskId(), task.fence(), code.code())); return null; }); }
    /** 任务状态供运维查询，不自动重置失败。 */
    public Task task(long id) { return mapper.task(id); }
    /** 原快照保留用于按广告受控修复。 */
    public Projection projection(long id) { return mapper.projection(id); }
    /** 固定状态指标，不以 adId 作为标签。 */
    public long count(State state) { return mapper.stateCount(state.code()); }
    /** 非法事件隔离不等于完成投影，独立展示待处理量。 */
    public long quarantinedCount() { return mapper.quarantinedCount(); }
    /** 依赖错误由调用方显示未知，不能降成零积压。 */
    public double oldestPendingSeconds() { return mapper.oldestPendingSeconds(); }

    /** 同 ID 重放返回原结果，不反复清空尝试次数。 */
    public long retryTask(UUID command, long id, String actor, String reason) {
        return recovery(command, Kind.RETRY_TASK.code(), id, actor, reason, () -> {
            if (mapper.retryTask(id) != 1) throw new Failure(NOT_ELIGIBLE);
            return id;
        });
    }
    /** Redis 丢失后的指定广告刷新仍排在未完成前驱后面，不能绕过暂停任务。 */
    public long refresh(UUID command, long adId, String actor, String reason) {
        return recovery(command, Kind.REFRESH_AD.code(), adId, actor, reason, () -> {
            mapper.adLock(adId);
            Projection projection = mapper.projection(adId);
            if (projection == null) throw new Failure(NOT_FOUND);
            return mapper.addTask(adId, projection.aggregateVersion(), projection.payload(), projection.payload());
        });
    }
    private long recovery(UUID command, String kind, long id, String actor, String reason, Supplier<Long> work) {
        CatalogProjectionProtocol.audit(command, actor, reason);
        return tx(() -> {
            mapper.controlLock();
            Audit previous = previous(command, kind, Long.toString(id), actor, reason);
            if (previous != null) return previous.resultId();
            long result = work.get();
            mapper.addAudit(new Audit(command.toString(), kind, Long.toString(id), actor, reason, result));
            return result;
        });
    }
    private Audit previous(UUID id, String kind, String target, String actor, String reason) {
        Audit old = mapper.audit(id.toString());
        if (old != null && (!old.kind().equals(kind) || !old.target().equals(target) || !old.actor().equals(actor) || !old.reason().equals(reason)))
            throw new Failure(PAYLOAD_CONFLICT);
        return old;
    }
    private Position position(int id) { return mapper.positions().stream().filter(p -> p.partitionId() == id).findFirst().orElseThrow(() -> new Failure(OFFSET_GAP)); }
    private void checkFence(String worker, long fence) { if (mapper.checkFence(worker, fence) != 1) throw new Failure(STALE_FENCE); }
    private static void requireOne(int affected) { if (affected != 1) throw new Failure(STALE_FENCE); }
    private <T> T tx(Supplier<T> work) { return transaction.execute(status -> { mapper.budget(); return work.get(); }); }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception invalid) { throw new Failure(INVALID_EVENT); } }
    private static String migrationDigest() {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) { return digest(input.readAllBytes()); }
        catch (Exception missing) { throw new Failure(SCHEMA_MISSING); }
    }
    private static String wireDigest(byte[] key, byte[] payload) {
        int keyLength = key == null ? -1 : key.length, payloadLength = payload == null ? -1 : payload.length;
        ByteBuffer bytes = ByteBuffer.allocate(8 + Math.max(0, keyLength) + Math.max(0, payloadLength));
        bytes.putInt(keyLength); if (key != null) bytes.put(key);
        bytes.putInt(payloadLength); if (payload != null) bytes.put(payload);
        return digest(bytes.array());
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception unavailable) { throw new Failure(DEPENDENCY_FAILURE); }
    }
}
