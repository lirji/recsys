package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ConversionSettlement.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** CPA结算所有SQL归同一adDb；写方法仅由持有quote父锁的短事务调用。 */
public class ConversionSettlementRepository {
    public static final String MIGRATION = "db/ad-billing/V013__conversion_settlement.sql";
    private static final String SNAPSHOT_COLUMNS = """
            s.*,s.outcome_id AS settled_outcome_id,q.subject_key AS owner_subject,q.amount,q.issued_at,q.expires_at,l.amount AS captured_amount
            """;
    private static final String SNAPSHOT_FROM = """
            FROM ad_conversion_settlement s JOIN ad_conversion_quote q ON q.quote_id=s.qualification_id
            LEFT JOIN ad_conversion_ledger l ON l.ledger_id=s.ledger_id AND l.qualification_id=s.qualification_id
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ConversionSettlementRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(5);
        this.json = json.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    /** 事务必须绑定同一个连接池，而非仅有相同URL。 */
    public javax.sql.DataSource dataSource() { return jdbc.getDataSource(); }

    /** 先扩展后启用，启动阶段只核对迁移，不自动修改数据库。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(jdbc.queryForObject(
                "SELECT script_sha256 FROM ad_billing_schema_history WHERE version=13",String.class))) {
            throw new IllegalStateException("CPA结算V013缺失或摘要漂移");
        }
        jdbc.queryForList("SELECT qualification_id,state FROM ad_conversion_settlement LIMIT 0");
        jdbc.queryForList("SELECT receipt_id,status FROM ad_conversion_visible LIMIT 0");
        jdbc.queryForList("SELECT outcome_id,status FROM ad_conversion_outcome LIMIT 0");
        jdbc.queryForList("SELECT ledger_id,outcome_id FROM ad_conversion_ledger LIMIT 0");
        jdbc.queryForList("SELECT event_id,delivery_state FROM ad_conversion_outbox LIMIT 0");
    }

    /** 不修改报价即可串行化尚不存在的资格，避免半成品准备行及反向锁序。 */
    public boolean lockQuote(UUID id) {
        return !jdbc.queryForList("SELECT quote_id FROM ad_conversion_quote WHERE quote_id=? FOR UPDATE",UUID.class,id).isEmpty();
    }
    /** 每轮只锁一个到期quote；所有前台用例也先锁此行。 */
    public Optional<UUID> lockOneExpired() {
        return jdbc.queryForList("""
                SELECT q.quote_id FROM ad_conversion_quote q JOIN ad_conversion_settlement s ON s.qualification_id=q.quote_id
                WHERE s.state='HELD' AND q.expires_at<=statement_timestamp()
                ORDER BY q.expires_at,q.quote_id LIMIT 1 FOR UPDATE OF q SKIP LOCKED
                """,UUID.class).stream().findFirst();
    }
    /** 工作器只能在已锁quote后读取原主体；不对外泄漏该值。 */
    public String owner(UUID id) { return jdbc.queryForObject("SELECT subject_key FROM ad_conversion_quote WHERE quote_id=?",String.class,id); }
    /** 一次查询同时读取账本与状态，避免两个READ_COMMITTED快照拼成假状态。 */
    public Optional<Snapshot> find(UUID id, String subject) {
        Reservation.validateSubject(subject);
        return jdbc.query("SELECT " + SNAPSHOT_COLUMNS + SNAPSHOT_FROM + " WHERE s.qualification_id=?",
                (rs,row)->snapshot(rs,subject),id).stream().findFirst();
    }
    /** 准备结果与账户预占同事务提交；主键保证一个报价只能预占一次。 */
    public Snapshot insert(Prepared p, String subject,long advertiserId) {
        requireOne(jdbc.update("""
                INSERT INTO ad_conversion_settlement(qualification_id,advertiser_id,budget_date,decision,qualification_token,prepared_at,state)
                VALUES(?,?,?,?,?,?,?)
                """,p.qualificationId(),advertiserId,p.budgetDate(),p.decision().name(),p.qualificationToken(),Timestamp.from(p.preparedAt()),
                p.decision()==Decision.HELD ? State.HELD.name() : State.REJECTED.name()),"CPA准备");
        return find(p.qualificationId(),subject).orElseThrow();
    }
    /** 广告主范围由已授权边界提供；条件在SQL中执行，不向回调方暴露展示主体。 */
    public Optional<Snapshot> findForAdvertiser(UUID id,long advertiserId) {
        return jdbc.query("SELECT "+SNAPSHOT_COLUMNS+SNAPSHOT_FROM+" WHERE s.qualification_id=? AND s.advertiser_id=?",
                (rs,row)->snapshot(rs,rs.getString("owner_subject")),id,advertiserId).stream().findFirst();
    }
    /** 唯一回执键先领取；冲突等待后恢复原完整请求。 */
    public boolean claimVisible(VisibleCommand c,UUID qualificationId) {
        return jdbc.update("""
                INSERT INTO ad_conversion_visible(receipt_id,qualification_id,subject_key,payload_hash,visible_duration_ms,min_visible_ratio_bps,page_visible)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """,c.receiptId(),qualificationId,c.subject(),c.payloadHash(),c.visibleDurationMs(),c.minVisibleRatioBps(),c.pageVisible())==1;
    }
    /** 原决定优先恢复，已保存命令不依赖当前验签key。 */
    public Optional<VisibleResult> originalVisible(VisibleCommand c) {
        return storedVisible(c.receiptId(),c.subject()).map(s->{if(!s.hash().equals(c.payloadHash()))throw new ConversionSettlement.Conflict();return s.result();});
    }
    /** 可见结果访问仍受原展示主体约束。 */
    public Optional<VisibleResult> findVisible(UUID id,String subject) { return storedVisible(id,subject).map(StoredVisible::result); }
    private Optional<StoredVisible> storedVisible(UUID id,String subject) {
        Reservation.validateSubject(subject);
        return jdbc.query("SELECT "+SNAPSHOT_COLUMNS+"""
                ,r.receipt_id,r.status,r.canonical_receipt_id,r.accepted_at,r.decided_at,r.payload_hash
                """+SNAPSHOT_FROM+" JOIN ad_conversion_visible r ON r.qualification_id=s.qualification_id WHERE r.receipt_id=?",
                (rs,row)->new StoredVisible(rs.getString("payload_hash"),new VisibleResult(visible(rs),snapshot(rs,subject))),id).stream().findFirst();
    }
    /** 原可见条件时间不因后来重试改变。 */
    public VisibleReceipt canonicalVisible(UUID id) {
        return jdbc.query("SELECT * FROM ad_conversion_visible WHERE receipt_id=?",(rs,row)->visible(rs),id).stream().findFirst().orElseThrow();
    }
    /** 首次可见决定与资金效果同事务。 */
    public void completeVisible(VisibleReceipt r) {
        requireOne(jdbc.update("""
                UPDATE ad_conversion_visible SET status=?,canonical_receipt_id=?,accepted_at=?,decided_at=? WHERE receipt_id=? AND status='PENDING'
                """,r.status().name(),r.canonicalReceiptId(),timestamp(r.acceptedAt()),timestamp(r.decidedAt()),r.receiptId()),"CPA可见决定");
    }
    /** 内部事实ID独立于广告主提供的eventId；广告主+业务键由数据库唯一约束兜底。 */
    public boolean claimOutcome(UUID outcomeId,ConversionCommand c,UUID qualificationId) {
        return jdbc.update("""
                INSERT INTO ad_conversion_outcome(outcome_id,advertiser_id,event_id,qualification_id,actor,payload_hash,occurred_at)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(advertiser_id,event_id) DO NOTHING
                """,outcomeId,c.advertiserId(),c.eventId(),qualificationId,c.actor(),c.payloadHash(),timestamp(c.occurredAt()))==1;
    }
    /** 同业务内容可由当前有权的另一个主体恢复，原actor只留作审计。 */
    public Optional<OutcomeResult> originalOutcome(ConversionCommand c) {
        return storedOutcome(c.advertiserId(),c.eventId()).map(s->{if(!s.hash().equals(c.payloadHash()))throw new ConversionSettlement.Conflict();return s.result();});
    }
    /** 同一SQL快照读取事实和当前结算，advertiser过滤不得遗漏。 */
    public Optional<OutcomeResult> findOutcome(long advertiserId,UUID eventId) { return storedOutcome(advertiserId,eventId).map(StoredOutcome::result); }
    private Optional<StoredOutcome> storedOutcome(long advertiserId,UUID eventId) {
        return jdbc.query("SELECT "+SNAPSHOT_COLUMNS+"""
                ,r.outcome_id AS event_outcome_id,r.event_id,r.occurred_at,r.received_at,r.status,r.canonical_outcome_id,r.decided_at,r.payload_hash
                """+SNAPSHOT_FROM+" JOIN ad_conversion_outcome r ON r.qualification_id=s.qualification_id WHERE r.advertiser_id=? AND r.event_id=?",
                (rs,row)->new StoredOutcome(rs.getString("payload_hash"),new OutcomeResult(outcome(rs),snapshot(rs,rs.getString("owner_subject")))) ,advertiserId,eventId).stream().findFirst();
    }
    /** 持有quote锁后获取canonical业务事实；仅内部捕获用于构造事件。 */
    public Outcome outcome(UUID id) {
        return jdbc.query("SELECT *,outcome_id AS event_outcome_id FROM ad_conversion_outcome WHERE outcome_id=?",(rs,row)->outcome(rs),id).stream().findFirst().orElseThrow();
    }
    /** 第一决定只写一次；新事实尚PENDING时可以先完成决定再构造捕获Outbox，仍在同事务。 */
    public void completeOutcome(UUID id,OutcomeStatus status,UUID canonical,Instant now) {
        requireOne(jdbc.update("UPDATE ad_conversion_outcome SET status=?,canonical_outcome_id=?,decided_at=? WHERE outcome_id=? AND status='PENDING'",
                status.name(),canonical,timestamp(now),id),"CPA业务事实决定");
    }
    /** 单次状态变化携带全部条件；数据库禁止两条件齐备却仍HELD的中间状态。 */
    public void advance(Snapshot s, UUID visible, UUID outcome, UUID ledger, State state, CloseReason reason, Instant now) {
        requireOne(jdbc.update("""
                UPDATE ad_conversion_settlement SET visible_receipt_id=?,outcome_id=?,ledger_id=?,state=?,close_reason=?,
                    closed_at=?,version=version+1,updated_at=? WHERE qualification_id=? AND state='HELD' AND version=?
                """,visible,outcome,ledger,state.name(),reason==null?null:reason.name(),state==State.HELD?null:timestamp(now),timestamp(now),
                s.prepared().qualificationId(),s.version()),"CPA状态迁移");
    }
    /** CPA和CPM使用同一账户锁和条件更新；释放与捕获都减原日held，只有捕获增加charged。 */
    public long debitHeld(BudgetAccount a,java.math.BigDecimal amount,boolean capture,Instant now) {
        requireOne(jdbc.update("""
                UPDATE ad_budget_account SET held_amount=held_amount-?,charged_amount=charged_amount+?,version=version+1,updated_at=?
                WHERE traffic_class='SANDBOX' AND advertiser_id=? AND budget_date=? AND currency='CNY' AND version=? AND held_amount>=?
                """,amount,capture?amount:java.math.BigDecimal.ZERO,timestamp(now),a.advertiserId(),a.budgetDate(),a.version(),amount),"CPA账户资金迁移");
        return a.version()+1;
    }
    /** 账本唯一约束覆盖资格和两种回执，任何冲突都回滚账户更新。 */
    public void appendLedger(UUID ledgerId,FrozenConversionQuote q,Snapshot s,UUID visible,UUID outcome,long accountVersion,Instant now) {
        requireOne(jdbc.update("""
                INSERT INTO ad_conversion_ledger(ledger_id,qualification_id,visible_receipt_id,outcome_id,operation_id,
                    advertiser_id,ad_id,creative_id,item_id,user_id,slot,bid_type,budget_date,amount,account_version,accepted_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,ledgerId,q.quoteId(),visible,outcome,q.operationId(),q.advertiserId(),q.adId(),q.creativeId(),q.itemId(),q.userId(),q.slot(),
                q.price().bidType().name(),s.prepared().budgetDate(),q.price().amount(),accountVersion,timestamp(now)),"CPA账本");
    }
    /** 仅在原本地事务落可靠待办；broker投递不能占用资金事务。 */
    public void appendOutbox(CapturedEvent event) {
        String payload;
        try { payload=json.writeValueAsString(event); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("CPA事件编码失败",ex); }
        requireOne(jdbc.update("""
                INSERT INTO ad_conversion_outbox(event_id,event_type,schema_version,destination,partition_key,payload_json)
                VALUES(?,?,?,?,?,?)
                """,UUID.fromString(event.eventId()),event.eventType(),event.schemaVersion(),CapturedEvent.TOPIC,
                event.advertiserId()+":"+event.budgetDate(),payload),"CPAOutbox");
    }
    private static Snapshot snapshot(ResultSet rs,String subject) throws SQLException {
        if (!subject.equals(rs.getString("owner_subject"))) throw new Reservation.AccessDenied();
        var p = new Prepared(rs.getObject("qualification_id",UUID.class),Decision.valueOf(rs.getString("decision")),
                rs.getObject("budget_date",LocalDate.class),rs.getBigDecimal("amount").toPlainString(),instant(rs,"issued_at"),
                instant(rs,"expires_at"),instant(rs,"prepared_at"),rs.getString("qualification_token"));
        String reason=rs.getString("close_reason"); var amount=rs.getBigDecimal("captured_amount");
        return new Snapshot(p,State.valueOf(rs.getString("state")),rs.getLong("version"),rs.getObject("visible_receipt_id",UUID.class),
                rs.getObject("settled_outcome_id",UUID.class),rs.getObject("ledger_id",UUID.class),amount==null?null:amount.toPlainString(),
                instant(rs,"closed_at"),reason==null?null:CloseReason.valueOf(reason));
    }
    private static VisibleReceipt visible(ResultSet rs) throws SQLException {
        if("PENDING".equals(rs.getString("status"))) throw new IllegalStateException("发现未完成CPA可见决定");
        return new VisibleReceipt(rs.getObject("receipt_id",UUID.class),rs.getObject("qualification_id",UUID.class),VisibleStatus.valueOf(rs.getString("status")),
                rs.getObject("canonical_receipt_id",UUID.class),instant(rs,"accepted_at"),instant(rs,"decided_at"));
    }
    private static Outcome outcome(ResultSet rs) throws SQLException {
        if("PENDING".equals(rs.getString("status"))) throw new IllegalStateException("发现未完成CPA业务决定");
        return new Outcome(rs.getObject("event_outcome_id",UUID.class),rs.getLong("advertiser_id"),rs.getObject("event_id",UUID.class),
                rs.getObject("qualification_id",UUID.class),instant(rs,"occurred_at"),instant(rs,"received_at"),OutcomeStatus.valueOf(rs.getString("status")),
                rs.getObject("canonical_outcome_id",UUID.class),instant(rs,"decided_at"));
    }
    private static Instant instant(ResultSet rs,String column) throws SQLException {
        var value=rs.getObject(column,OffsetDateTime.class); return value==null?null:value.toInstant();
    }
    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
    private static void requireOne(int rows,String effect) { if(rows!=1) throw new IllegalStateException(effect+"未生效"); }
    private record StoredVisible(String hash,VisibleResult result) {}
    private record StoredOutcome(String hash,OutcomeResult result) {}
}
