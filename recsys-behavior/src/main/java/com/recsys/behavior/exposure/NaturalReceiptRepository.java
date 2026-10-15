package com.recsys.behavior.exposure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.recsys.common.exposure.NaturalQualification;
import com.recsys.common.exposure.NaturalReceipt;
import com.recsys.common.exposure.NaturalReceipt.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** behavior owner 的唯一自然事实写入入口；所有写入由同一用例短事务包围。 */
public class NaturalReceiptRepository {
    public static final String MIGRATION = "db/natural-exposure/V001__natural_receipt.sql";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public NaturalReceiptRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    /** 事务绑定必须为同一个连接池，不以 URL 相同代替同一资源。 */
    public DataSource dataSource() { return jdbc.getDataSource(); }
    /** 启动只校验，不隐式修改共享库。 */
    public void verifySchema() {
        if (!migrationSha256().equals(jdbc.queryForObject("SELECT script_sha256 FROM natural_exposure_schema_history WHERE version=1", String.class))) {
            throw new IllegalStateException("自然曝光迁移缺失或摘要漂移");
        }
        jdbc.queryForList("SELECT qualification_id,context_hash FROM natural_qualification_guard LIMIT 0");
        jdbc.queryForList("SELECT receipt_id,status FROM natural_impression_receipt LIMIT 0");
        jdbc.queryForList("SELECT qualification_id,receipt_id FROM natural_impression_fact LIMIT 0");
        jdbc.queryForList("SELECT event_id,payload_json FROM natural_impression_outbox LIMIT 0");
    }
    /** 包内脚本摘要与显式迁移记录一致。 */
    public static String migrationSha256() {
        try (var in = new ClassPathResource(MIGRATION).getInputStream()) {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("自然迁移不可读", ex); }
    }
    /** 当前语句时间在锁之后重新获取，不使用事务启动时间。 */
    public Instant databaseNow() { return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant(); }
    /** 限制数据库锁和语句等待，事务超时不依赖客户端主动取消。 */
    public void boundTransaction() {
        jdbc.execute("SET LOCAL lock_timeout = '4s'");
        jdbc.execute("SET LOCAL statement_timeout = '5s'");
    }
    /** 幂等键抢占等待另一事务完成；约束阻止 PENDING 单独提交。 */
    public boolean claim(Command c, UUID qualificationId) {
        return jdbc.update("""
                INSERT INTO natural_impression_receipt(receipt_id,qualification_id,subject_key,payload_hash,
                    visible_duration_ms,min_visible_ratio_bps,page_visible) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, c.receiptId(), qualificationId, c.subject(), c.payloadHash(), c.visibleDurationMs(), c.minVisibleRatioBps(), c.pageVisible()) == 1;
    }
    /** 不含签名 keyId 的规范上下文摘要允许轮换验证 key，禁止同资格改 item/桶/期限。 */
    public void lockQualification(NaturalQualification q) {
        String context = write(q), hash = com.recsys.common.exposure.NaturalQualificationToken.contextHash(q);
        jdbc.update("""
                INSERT INTO natural_qualification_guard(qualification_id,subject_key,context_hash,context_json)
                VALUES (?,?,?,?) ON CONFLICT DO NOTHING
                """, q.qualificationId(), q.subject(), hash, context);
        jdbc.queryForObject("SELECT subject_key,context_hash FROM natural_qualification_guard WHERE qualification_id=? FOR UPDATE", (rs, row) -> {
            if (!q.subject().equals(rs.getString("subject_key"))) throw new AccessDenied();
            if (!hash.equals(rs.getString("context_hash"))) throw new Conflict();
            return true;
        }, q.qualificationId());
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
        return jdbc.query("SELECT * FROM natural_impression_receipt WHERE receipt_id=?", (rs, row) -> {
            if (!subject.equals(rs.getString("subject_key"))) throw new AccessDenied();
            var accepted = rs.getObject("accepted_at", OffsetDateTime.class);
            var decided = rs.getObject("decided_at", OffsetDateTime.class);
            if (decided == null) throw new IllegalStateException("出现已提交半成品回执");
            return new Stored(rs.getString("payload_hash"), new Result(id, rs.getObject("qualification_id", UUID.class),
                    Status.valueOf(rs.getString("status")), rs.getObject("event_id", UUID.class), rs.getObject("canonical_receipt_id", UUID.class),
                    accepted == null ? null : accepted.toInstant(), decided.toInstant()));
        }, id).stream().findFirst();
    }
    /** 已接受的资格引用同一事实；重复本身不写第二条事实/事件。 */
    public Optional<Result> accepted(UUID qualificationId, UUID receiptId, Instant now) {
        return jdbc.query("SELECT receipt_id,accepted_at FROM natural_impression_fact WHERE qualification_id=?", (rs, row) ->
                new Result(receiptId, qualificationId, Status.ALREADY_ACCEPTED, qualificationId,
                        rs.getObject("receipt_id", UUID.class), rs.getObject("accepted_at", OffsetDateTime.class).toInstant(), now),
                qualificationId).stream().findFirst();
    }
    /** 唯一约束最后守住资格事实；数据库要求提交时相应 Outbox 必须存在。 */
    public void appendFact(NaturalQualification q, UUID receiptId, Instant now) {
        requireOne(jdbc.update("INSERT INTO natural_impression_fact(qualification_id,receipt_id,traffic_class,accepted_at) VALUES (?,?,'SANDBOX',?)",
                q.qualificationId(), receiptId, Timestamp.from(now)));
    }
    /** 不调用 Kafka；仅在与事实相同事务内保存不可变事件。 */
    public void appendOutbox(Event event) {
        requireOne(jdbc.update("""
                INSERT INTO natural_impression_outbox(event_id,event_type,schema_version,destination,partition_key,payload_json)
                VALUES (?,?,?,?,?,?)
                """, UUID.fromString(event.eventId()), Event.TYPE, Event.VERSION, Event.TOPIC, event.userId(), write(event)));
    }
    /** 首次决定只可从事务内 PENDING 更新一次，条件失配不能伪装成功。 */
    public Result complete(Result r) {
        requireOne(jdbc.update("""
                UPDATE natural_impression_receipt SET status=?,event_id=?,canonical_receipt_id=?,accepted_at=?,decided_at=?
                WHERE receipt_id=? AND status='PENDING'
                """, r.status().name(), r.eventId(), r.canonicalReceiptId(), r.acceptedAt() == null ? null : Timestamp.from(r.acceptedAt()),
                Timestamp.from(r.decidedAt()), r.receiptId()));
        return r;
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("自然事件/上下文无法编码"); }
    }
    private static void requireOne(int rows) { if (rows != 1) throw new IllegalStateException("自然事实写入未生效"); }
    private record Stored(String hash, Result result) {}
}
