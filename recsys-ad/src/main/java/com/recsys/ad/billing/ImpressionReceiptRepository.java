package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ImpressionReceipt.*;
import com.recsys.common.ad.delivery.SandboxDelivery.Qualification;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** 回执、不可变账本和 Outbox 的同库持久化入口；写方法必须由 owner 用例事务调用。 */
public class ImpressionReceiptRepository {
    public static final String MIGRATION = "db/ad-billing/V005__impression_capture.sql";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ImpressionReceiptRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); }
    /** 同一 URL 的另一连接池仍不是同一事务资源。 */
    public javax.sql.DataSource dataSource() { return jdbc.getDataSource(); }

    /** 首次启用接受前核对 V005，缺少任一事实表不能收单。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(jdbc.queryForObject(
                "SELECT script_sha256 FROM ad_billing_schema_history WHERE version=5", String.class))) {
            throw new IllegalStateException("adDb V005 捕获迁移缺失或摘要漂移");
        }
        jdbc.queryForList("SELECT receipt_id, ledger_id, status FROM ad_impression_receipt LIMIT 0");
        jdbc.queryForList("SELECT ledger_id, qualification_id FROM ad_billing_ledger LIMIT 0");
        jdbc.queryForList("SELECT event_id, payload_json, published_at FROM ad_billing_outbox LIMIT 0");
    }

    /** 同一命令并发通过唯一键等待首个事务，不能在结果未知时换 ID。 */
    public boolean claim(Command command, UUID qualificationId) {
        return jdbc.update("""
                INSERT INTO ad_impression_receipt(receipt_id, qualification_id, subject_key, payload_hash,
                    visible_duration_ms, min_visible_ratio_bps, page_visible)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, command.receiptId(), qualificationId, command.subject(), command.payloadHash(), command.visibleDurationMs(),
                command.minVisibleRatioBps(), command.pageVisible()) == 1;
    }

    /** 按命令查询时还核对负载摘要；先返回历史结果允许 key 轮换后恢复旧 ACK。 */
    public Optional<Result> original(Command command) {
        return findStored(command.receiptId(), command.subject()).map(stored -> {
            if (!stored.hash().equals(command.payloadHash())) throw new ImpressionReceipt.Conflict();
            return stored.result();
        });
    }
    /** 对外查询只返回原结果，token 和负载摘要不暴露。 */
    public Optional<Result> find(UUID receiptId, String subject) {
        Reservation.validateSubject(subject);
        return findStored(receiptId, subject).map(Stored::result);
    }
    private Optional<Stored> findStored(UUID receiptId, String subject) {
        return jdbc.query("SELECT * FROM ad_impression_receipt WHERE receipt_id=?", (rs, row) -> {
            if (!subject.equals(rs.getString("subject_key"))) throw new Reservation.AccessDenied();
            if ("PENDING".equals(rs.getString("status"))) throw new IllegalStateException("出现未完成的已提交回执");
            OffsetDateTime accepted = rs.getObject("accepted_at", OffsetDateTime.class);
            var result = new Result(receiptId, rs.getObject("qualification_id", UUID.class), Status.valueOf(rs.getString("status")),
                    rs.getObject("ledger_id", UUID.class), rs.getObject("canonical_receipt_id", UUID.class), rs.getBigDecimal("charged_amount").toPlainString(),
                    rs.getObject("budget_date", LocalDate.class), accepted == null ? null : accepted.toInstant(),
                    rs.getObject("decided_at", OffsetDateTime.class).toInstant());
            return new Stored(rs.getString("payload_hash"), result);
        }, receiptId).stream().findFirst();
    }

    /** 唯一 ledger 决定资格是否已经收费，后到事件不能因账户版本更高而漏记原事实。 */
    public Optional<Result> alreadyCaptured(UUID qualificationId, UUID currentReceiptId, Instant now) {
        return jdbc.query("SELECT * FROM ad_billing_ledger WHERE qualification_id=?", (rs, row) ->
                new Result(currentReceiptId, qualificationId, Status.ALREADY_ACCEPTED, rs.getObject("ledger_id", UUID.class),
                        rs.getObject("receipt_id", UUID.class), rs.getBigDecimal("amount").toPlainString(),
                        rs.getObject("budget_date", LocalDate.class), rs.getObject("accepted_at", OffsetDateTime.class).toInstant(), now),
                qualificationId).stream().findFirst();
    }

    /** 捕获唯一事实插入失败必须回滚 held→charged 与预占终态。 */
    public void appendLedger(UUID ledgerId, Command command, Qualification qualification, Reservation reservation,
                             long accountVersion, Instant acceptedAt) {
        var held = reservation.prepared();
        int rows = jdbc.update("""
                INSERT INTO ad_billing_ledger(ledger_id, qualification_id, receipt_id, operation_id, advertiser_id, ad_id,
                    creative_id, user_id, slot, traffic_class, currency, budget_date, amount, account_version, accepted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SANDBOX', 'CNY', ?, ?, ?, ?)
                """, ledgerId, qualification.childId(), command.receiptId(), qualification.operationId(), held.advertiserId(), held.adId(),
                qualification.creativeId(), qualification.userId(), qualification.slot(), held.budgetDate(), held.amount(), accountVersion, Timestamp.from(acceptedAt));
        if (rows != 1) throw new IllegalStateException("账本事实未写入");
    }

    /** 与资金和 ledger 同事务写待发事件，不在此处执行任何 broker 调用。 */
    public void appendOutbox(CapturedEvent event) {
        String payload;
        try { payload = json.writeValueAsString(event); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("账本事件无法编码"); }
        int rows = jdbc.update("""
                INSERT INTO ad_billing_outbox(event_id,event_type,schema_version,destination,partition_key,payload_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.fromString(event.eventId()), event.eventType(), event.schemaVersion(), CapturedEvent.TOPIC,
                event.advertiserId() + ":" + event.budgetDate(), payload);
        if (rows != 1) throw new IllegalStateException("账本事件未写入");
    }

    /** 业务拒绝与接受都保存首次结果；失败使此前资金/事实更新整体回滚。 */
    public Result complete(Result result) {
        int rows = jdbc.update("""
                UPDATE ad_impression_receipt SET status=?, ledger_id=?, canonical_receipt_id=?, charged_amount=?,
                    budget_date=?, accepted_at=?, decided_at=? WHERE receipt_id=? AND status='PENDING'
                """, result.status().name(), result.ledgerId(), result.canonicalReceiptId(), CnyAmount.parse(result.chargedAmount()),
                result.budgetDate(), result.acceptedAt() == null ? null : Timestamp.from(result.acceptedAt()),
                Timestamp.from(result.decidedAt()), result.receiptId());
        if (rows != 1) throw new IllegalStateException("回执结果保存未生效");
        return result;
    }
    private record Stored(String hash, Result result) {}
}
