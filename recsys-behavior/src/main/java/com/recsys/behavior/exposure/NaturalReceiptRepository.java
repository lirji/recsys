package com.recsys.behavior.exposure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.recsys.common.exposure.NaturalQualification;
import com.recsys.common.exposure.NaturalReceipt;
import com.recsys.common.exposure.NaturalReceipt.*;
import org.springframework.core.io.ClassPathResource;
import com.recsys.behavior.persistence.mapper.primary.NaturalReceiptMapper;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** behavior owner 的唯一自然事实写入入口；所有写入由同一用例短事务包围。 */
public class NaturalReceiptRepository {
    public static final String MIGRATION = "db/natural-exposure/V001__natural_receipt.sql";
    private final NaturalReceiptMapper mapper;
    private final DataSource dataSource;
    private final ObjectMapper json;
    /** Mapper与事务管理器必须共用此DataSource实例，JSON编码策略保持原样。 */
    public NaturalReceiptRepository(NaturalReceiptMapper mapper, DataSource dataSource, ObjectMapper json) {
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    /** 事务绑定必须为同一个连接池，不以 URL 相同代替同一资源。 */
    public DataSource dataSource() { return dataSource; }
    /** 启动只校验，不隐式修改共享库。 */
    public void verifySchema() {
        if (!migrationSha256().equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes()))) {
            throw new IllegalStateException("自然曝光迁移缺失或摘要漂移");
        }
        mapper.verifyGuard();
        mapper.verifyReceipt();
        mapper.verifyFact();
        mapper.verifyOutbox();
    }
    /** 包内脚本摘要与显式迁移记录一致。 */
    public static String migrationSha256() {
        try (var in = new ClassPathResource(MIGRATION).getInputStream()) {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("自然迁移不可读", ex); }
    }
    /** 当前语句时间在锁之后重新获取，不使用事务启动时间。 */
    public Instant databaseNow() { return mapper.databaseNow().toInstant(); }
    /** 限制数据库锁和语句等待，事务超时不依赖客户端主动取消。 */
    public void boundTransaction() {
        mapper.boundLocks();
        mapper.boundStatements();
    }
    /** 幂等键抢占等待另一事务完成；约束阻止 PENDING 单独提交。 */
    public boolean claim(Command c, UUID qualificationId) {
        return mapper.claim(new NaturalReceiptMapper.Claim(c.receiptId(), qualificationId, c.subject(), c.payloadHash(),
                c.visibleDurationMs(), c.minVisibleRatioBps(), c.pageVisible())) == 1;
    }
    /** 不含签名 keyId 的规范上下文摘要允许轮换验证 key，禁止同资格改 item/桶/期限。 */
    public void lockQualification(NaturalQualification q) {
        String context = write(q), hash = com.recsys.common.exposure.NaturalQualificationToken.contextHash(q);
        mapper.insertGuard(new NaturalReceiptMapper.GuardWrite(q.qualificationId(), q.subject(), hash, context));
        var guard = mapper.lockGuard(q.qualificationId());
        if (guard == null) throw new EmptyResultDataAccessException(1);
        if (!q.subject().equals(guard.subjectKey())) throw new AccessDenied();
        if (!hash.equals(guard.contextHash())) throw new Conflict();
    }
    /** 相同命令返回原决定；先检查主体，避免向其他身份泄漏结果。 */
    public Optional<Result> original(Command c) {
        return stored(c.receiptId(), c.subject()).map(s -> {
            if (!s.hash().equals(c.payloadHash())) throw new Conflict();
            return s.result();
        });
    }
    /** GET 不验现行 key/Policy、不领取锁，原结果始终可恢复。 */
    public Optional<Result> find(UUID id, String subject) { return stored(id, subject).map(Stored::result); }
    private Optional<Stored> stored(UUID id, String subject) {
        NaturalReceipt.validateSubject(subject);
        return mapper.receipts(id).stream().findFirst().map(row -> {
            if (!subject.equals(row.subjectKey())) throw new AccessDenied();
            if (row.decidedAt() == null) throw new IllegalStateException("出现已提交半成品回执");
            return new Stored(row.payloadHash(), new Result(id, row.qualificationId(), Status.valueOf(row.status()),
                    row.eventId(), row.canonicalReceiptId(), row.acceptedAt() == null ? null : row.acceptedAt().toInstant(),
                    row.decidedAt().toInstant()));
        });
    }
    /** 已接受的资格引用同一事实；重复本身不写第二条事实/事件。 */
    public Optional<Result> accepted(UUID qualificationId, UUID receiptId, Instant now) {
        return mapper.accepted(qualificationId).stream().findFirst().map(row ->
                new Result(receiptId, qualificationId, Status.ALREADY_ACCEPTED, qualificationId,
                        row.receiptId(), row.acceptedAt().toInstant(), now));
    }
    /** 唯一约束最后守住资格事实；数据库要求提交时相应 Outbox 必须存在。 */
    public void appendFact(NaturalQualification q, UUID receiptId, Instant now) {
        requireOne(mapper.appendFact(q.qualificationId(), receiptId, Timestamp.from(now)));
    }
    /** 不调用 Kafka；仅在与事实相同事务内保存不可变事件。 */
    public void appendOutbox(Event event) {
        requireOne(mapper.appendOutbox(new NaturalReceiptMapper.OutboxWrite(UUID.fromString(event.eventId()),
                Event.TYPE, Event.VERSION, Event.TOPIC, event.userId(), write(event))));
    }
    /** 首次决定只可从事务内 PENDING 更新一次，条件失配不能伪装成功。 */
    public Result complete(Result r) {
        requireOne(mapper.complete(new NaturalReceiptMapper.Decision(r.receiptId(), r.status().name(), r.eventId(),
                r.canonicalReceiptId(), r.acceptedAt() == null ? null : Timestamp.from(r.acceptedAt()), Timestamp.from(r.decidedAt()))));
        return r;
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("自然事件/上下文无法编码"); }
    }
    private static void requireOne(int rows) { if (rows != 1) throw new IllegalStateException("自然事实写入未生效"); }
    private record Stored(String hash, Result result) {}
}
