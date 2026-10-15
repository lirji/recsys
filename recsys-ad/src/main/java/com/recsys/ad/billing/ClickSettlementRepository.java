package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ClickSettlement.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 点击结算所有SQL归同一adDb；写方法仅由持有quote父锁的短事务调用。 */
public class ClickSettlementRepository {
    public static final String MIGRATION = "db/ad-billing/V009__click_settlement.sql";
    private static final String SNAPSHOT_COLUMNS = """
            s.*,q.subject_key AS owner_subject,q.amount,q.issued_at,q.expires_at,l.amount AS captured_amount
            """;
    private static final String SNAPSHOT_FROM = """
            FROM ad_click_settlement s JOIN ad_click_quote q ON q.quote_id=s.qualification_id
            LEFT JOIN ad_click_ledger l ON l.ledger_id=s.ledger_id AND l.qualification_id=s.qualification_id
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ClickSettlementRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(5);
        this.json = json.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    /** 事务必须绑定同一个连接池，而非仅有相同URL。 */
    public javax.sql.DataSource dataSource() { return jdbc.getDataSource(); }

    /** 先扩展后启用，启动阶段只核对迁移，不自动修改数据库。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(jdbc.queryForObject(
                "SELECT script_sha256 FROM ad_billing_schema_history WHERE version=9",String.class))) {
            throw new IllegalStateException("点击结算V009缺失或摘要漂移");
        }
        jdbc.queryForList("SELECT qualification_id,state FROM ad_click_settlement LIMIT 0");
        jdbc.queryForList("SELECT receipt_id,status FROM ad_click_receipt LIMIT 0");
        jdbc.queryForList("SELECT user_id,accepted_count FROM ad_click_rate LIMIT 0");
        jdbc.queryForList("SELECT ledger_id,click_receipt_id FROM ad_click_ledger LIMIT 0");
        jdbc.queryForList("SELECT event_id,delivery_state FROM ad_click_outbox LIMIT 0");
    }

    /** 不修改报价即可串行化尚不存在的资格，避免半成品准备行及反向锁序。 */
    public boolean lockQuote(UUID id) {
        return !jdbc.queryForList("SELECT quote_id FROM ad_click_quote WHERE quote_id=? FOR UPDATE",UUID.class,id).isEmpty();
    }
    /** 每轮只锁一个到期quote；所有前台用例也先锁此行。 */
    public Optional<UUID> lockOneExpired() {
        return jdbc.queryForList("""
                SELECT q.quote_id FROM ad_click_quote q JOIN ad_click_settlement s ON s.qualification_id=q.quote_id
                WHERE s.state='HELD' AND q.expires_at<=statement_timestamp()
                ORDER BY q.expires_at,q.quote_id LIMIT 1 FOR UPDATE OF q SKIP LOCKED
                """,UUID.class).stream().findFirst();
    }
    /** 工作器只能在已锁quote后读取原主体；不对外泄漏该值。 */
    public String owner(UUID id) { return jdbc.queryForObject("SELECT subject_key FROM ad_click_quote WHERE quote_id=?",String.class,id); }
    /** 一次查询同时读取账本与状态，避免两个READ_COMMITTED快照拼成假状态。 */
    public Optional<Snapshot> find(UUID id, String subject) {
        Reservation.validateSubject(subject);
        return jdbc.query("SELECT " + SNAPSHOT_COLUMNS + SNAPSHOT_FROM + " WHERE s.qualification_id=?",
                (rs,row)->snapshot(rs,subject),id).stream().findFirst();
    }
    /** 准备结果与账户预占同事务提交；主键保证一个报价只能预占一次。 */
    public Snapshot insert(Prepared p, String subject) {
        requireOne(jdbc.update("""
                INSERT INTO ad_click_settlement(qualification_id,budget_date,decision,qualification_token,prepared_at,state)
                VALUES(?,?,?,?,?,?)
                """,p.qualificationId(),p.budgetDate(),p.decision().name(),p.qualificationToken(),Timestamp.from(p.preparedAt()),
                p.decision()==Decision.HELD ? State.HELD.name() : State.REJECTED.name()),"点击准备");
        return find(p.qualificationId(),subject).orElseThrow();
    }
    /** 唯一键冲突会等待另一命令事务，之后比较完整摘要，绝不换幂等ID。 */
    public boolean claim(Command c, UUID qualificationId) {
        return jdbc.update("""
                INSERT INTO ad_click_receipt(receipt_id,qualification_id,subject_key,payload_hash,kind,
                    visible_duration_ms,min_visible_ratio_bps,page_visible) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """,c.receiptId(),qualificationId,c.subject(),c.payloadHash(),c.kind().name(),c.visibleDurationMs(),c.minVisibleRatioBps(),c.pageVisible())==1;
    }
    /** 历史同命令先恢复，允许旧签名密钥退出后查询已提交结果。 */
    public Optional<Result> original(Command c) {
        return stored(c.receiptId(),c.subject()).map(stored->{
            if (!stored.hash().equals(c.payloadHash())) throw new ClickSettlement.Conflict();
            return stored.result();
        });
    }
    /** 回执原决定和当前结算状态来自同一SQL快照。 */
    public Optional<Result> findReceipt(UUID id,String subject) { return stored(id,subject).map(Stored::result); }
    private Optional<Stored> stored(UUID id,String subject) {
        Reservation.validateSubject(subject);
        return jdbc.query("SELECT " + SNAPSHOT_COLUMNS + """
                ,r.receipt_id,r.kind,r.status,r.canonical_receipt_id,r.accepted_at,r.decided_at,r.payload_hash
                """ + SNAPSHOT_FROM + " JOIN ad_click_receipt r ON r.qualification_id=s.qualification_id WHERE r.receipt_id=?",
                (rs,row)->new Stored(rs.getString("payload_hash"),new Result(receipt(rs),snapshot(rs,subject))),id).stream().findFirst();
    }
    /** 原种类的canonical接受时间单独保存，重复回执不重测或修改该时间。 */
    public Receipt canonical(UUID id) {
        return jdbc.query("SELECT * FROM ad_click_receipt WHERE receipt_id=?",(rs,row)->receipt(rs),id).stream().findFirst().orElseThrow();
    }
    /** 首次决定写成后不可再修改；失败回滚同事务的计数/资金。 */
    public void complete(Receipt r) {
        requireOne(jdbc.update("""
                UPDATE ad_click_receipt SET status=?,canonical_receipt_id=?,accepted_at=?,decided_at=?
                WHERE receipt_id=? AND status='PENDING'
                """,r.status().name(),r.canonicalReceiptId(),timestamp(r.acceptedAt()),timestamp(r.decidedAt()),r.receiptId()),"点击回执决定");
    }
    /** rate先于账户加锁；每用户一行，不积累逐分钟行，也不在DB故障时放行。 */
    public void lockRate(long userId) {
        jdbc.update("""
                INSERT INTO ad_click_rate(user_id,minute_start,accepted_count) VALUES(?,date_trunc('minute',clock_timestamp()),0)
                ON CONFLICT DO NOTHING
                """,userId);
        jdbc.queryForObject("SELECT user_id FROM ad_click_rate WHERE user_id=? FOR UPDATE",Long.class,userId);
    }
    /** 持有全部锁后才按本次决策分钟计数；超限不自增，原资格随后永久拒绝并释放。 */
    public boolean countClick(long userId,int limit,Instant now) {
        return jdbc.update("""
                UPDATE ad_click_rate SET minute_start=date_trunc('minute',?::timestamptz),
                    accepted_count=CASE WHEN minute_start=date_trunc('minute',?::timestamptz) THEN accepted_count+1 ELSE 1 END,
                    updated_at=? WHERE user_id=? AND (minute_start<>date_trunc('minute',?::timestamptz) OR accepted_count<?)
                """,timestamp(now),timestamp(now),timestamp(now),userId,timestamp(now),limit)==1;
    }
    /** 单次状态变化携带全部条件；数据库禁止两条件齐备却仍HELD的中间状态。 */
    public void advance(Snapshot s, UUID visible, UUID click, UUID ledger, State state, CloseReason reason, Instant now) {
        requireOne(jdbc.update("""
                UPDATE ad_click_settlement SET visible_receipt_id=?,click_receipt_id=?,ledger_id=?,state=?,close_reason=?,
                    closed_at=?,version=version+1,updated_at=? WHERE qualification_id=? AND state='HELD' AND version=?
                """,visible,click,ledger,state.name(),reason==null?null:reason.name(),state==State.HELD?null:timestamp(now),timestamp(now),
                s.prepared().qualificationId(),s.version()),"点击状态迁移");
    }
    /** 点击和CPM使用同一账户锁和条件更新；释放与捕获都减原日held，只有捕获增加charged。 */
    public long debitHeld(BudgetAccount a,java.math.BigDecimal amount,boolean capture,Instant now) {
        requireOne(jdbc.update("""
                UPDATE ad_budget_account SET held_amount=held_amount-?,charged_amount=charged_amount+?,version=version+1,updated_at=?
                WHERE traffic_class='SANDBOX' AND advertiser_id=? AND budget_date=? AND currency='CNY' AND version=? AND held_amount>=?
                """,amount,capture?amount:java.math.BigDecimal.ZERO,timestamp(now),a.advertiserId(),a.budgetDate(),a.version(),amount),"点击账户资金迁移");
        return a.version()+1;
    }
    /** 账本唯一约束覆盖资格和两种回执，任何冲突都回滚账户更新。 */
    public void appendLedger(UUID ledgerId,FrozenClickQuote q,Snapshot s,UUID visible,UUID click,long accountVersion,Instant now) {
        requireOne(jdbc.update("""
                INSERT INTO ad_click_ledger(ledger_id,qualification_id,visible_receipt_id,click_receipt_id,operation_id,
                    advertiser_id,ad_id,creative_id,item_id,user_id,slot,bid_type,budget_date,amount,account_version,accepted_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,ledgerId,q.quoteId(),visible,click,q.operationId(),q.advertiserId(),q.adId(),q.creativeId(),q.itemId(),q.userId(),q.slot(),
                q.price().bidType().name(),s.prepared().budgetDate(),q.price().amount(),accountVersion,timestamp(now)),"点击账本");
    }
    /** 仅在原本地事务落可靠待办；broker投递不能占用资金事务。 */
    public void appendOutbox(CapturedEvent event) {
        String payload;
        try { payload=json.writeValueAsString(event); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("点击事件编码失败",ex); }
        requireOne(jdbc.update("""
                INSERT INTO ad_click_outbox(event_id,event_type,schema_version,destination,partition_key,payload_json)
                VALUES(?,?,?,?,?,?)
                """,UUID.fromString(event.eventId()),event.eventType(),event.schemaVersion(),CapturedEvent.TOPIC,
                event.advertiserId()+":"+event.budgetDate(),payload),"点击Outbox");
    }
    private static Snapshot snapshot(ResultSet rs,String subject) throws SQLException {
        if (!subject.equals(rs.getString("owner_subject"))) throw new Reservation.AccessDenied();
        var p = new Prepared(rs.getObject("qualification_id",UUID.class),Decision.valueOf(rs.getString("decision")),
                rs.getObject("budget_date",LocalDate.class),rs.getBigDecimal("amount").toPlainString(),instant(rs,"issued_at"),
                instant(rs,"expires_at"),instant(rs,"prepared_at"),rs.getString("qualification_token"));
        String reason=rs.getString("close_reason"); var amount=rs.getBigDecimal("captured_amount");
        return new Snapshot(p,State.valueOf(rs.getString("state")),rs.getLong("version"),rs.getObject("visible_receipt_id",UUID.class),
                rs.getObject("click_receipt_id",UUID.class),rs.getObject("ledger_id",UUID.class),amount==null?null:amount.toPlainString(),
                instant(rs,"closed_at"),reason==null?null:CloseReason.valueOf(reason));
    }
    private static Receipt receipt(ResultSet rs) throws SQLException {
        if ("PENDING".equals(rs.getString("status"))) throw new IllegalStateException("发现未完成点击回执");
        return new Receipt(rs.getObject("receipt_id",UUID.class),rs.getObject("qualification_id",UUID.class),Kind.valueOf(rs.getString("kind")),
                ReceiptStatus.valueOf(rs.getString("status")),rs.getObject("canonical_receipt_id",UUID.class),instant(rs,"accepted_at"),instant(rs,"decided_at"));
    }
    private static Instant instant(ResultSet rs,String column) throws SQLException {
        var value=rs.getObject(column,OffsetDateTime.class); return value==null?null:value.toInstant();
    }
    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
    private static void requireOne(int rows,String effect) { if(rows!=1) throw new IllegalStateException(effect+"未生效"); }
    private record Stored(String hash,Result result) {}
}
