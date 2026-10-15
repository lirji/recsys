package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ClickSettlement.*;
import com.recsys.ad.persistence.mapper.addb.ClickSettlementMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 点击结算所有SQL归同一adDb；写方法仅由持有quote父锁的短事务调用。 */
public class ClickSettlementRepository {
    public static final String MIGRATION = "db/ad-billing/V009__click_settlement.sql";
    /** PENDING仅为事务内部占位，不扩展公开回执决定枚举。 */
    private static final String UNFINISHED_RECEIPT_STATUS = "PENDING";
    private final ClickSettlementMapper mapper;
    private final DataSource dataSource;
    private final ObjectMapper json;
    /** 实际会话工厂决定owner资源，XML逐语句保留原5秒查询超时。 */
    public ClickSettlementRepository(SqlSessionTemplate sessions, ObjectMapper json) {
        this.mapper = sessions.getMapper(ClickSettlementMapper.class);
        this.dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
        this.json = json.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    /** 事务必须绑定同一个连接池，而非仅有相同URL。 */
    public javax.sql.DataSource dataSource() { return dataSource; }

    /** 先扩展后启用，启动阶段只核对迁移，不自动修改数据库。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes()))) {
            throw new IllegalStateException("点击结算V009缺失或摘要漂移");
        }
        mapper.verifySettlement(); mapper.verifyReceipt(); mapper.verifyRate(); mapper.verifyLedger(); mapper.verifyOutbox();
    }

    /** 不修改报价即可串行化尚不存在的资格，避免半成品准备行及反向锁序。 */
    public boolean lockQuote(UUID id) {
        return !mapper.lockQuote(id).isEmpty();
    }
    /** 每轮只锁一个到期quote；所有前台用例也先锁此行。 */
    public Optional<UUID> lockOneExpired() {
        return mapper.lockOneExpired().stream().findFirst();
    }
    /** 工作器只能在已锁quote后读取原主体；不对外泄漏该值。 */
    public String owner(UUID id) { return DataAccessUtils.requiredSingleResult(mapper.owner(id)); }
    /** 一次查询同时读取账本与状态，避免两个READ_COMMITTED快照拼成假状态。 */
    public Optional<Snapshot> find(UUID id, String subject) {
        Reservation.validateSubject(subject);
        return mapper.find(id).stream().findFirst().map(row -> snapshot(row, subject));
    }
    /** 准备结果与账户预占同事务提交；主键保证一个报价只能预占一次。 */
    public Snapshot insert(Prepared p, String subject) {
        requireOne(mapper.insert(new ClickSettlementMapper.Preparation(p.qualificationId(), p.budgetDate(), p.decision().name(),
                p.qualificationToken(), timestamp(p.preparedAt()), p.decision() == Decision.HELD ? State.HELD.name() : State.REJECTED.name())), "点击准备");
        return find(p.qualificationId(),subject).orElseThrow();
    }
    /** 唯一键冲突会等待另一命令事务，之后比较完整摘要，绝不换幂等ID。 */
    public boolean claim(Command c, UUID qualificationId) {
        return mapper.claim(new ClickSettlementMapper.Claim(c.receiptId(), qualificationId, c.subject(), c.payloadHash(), c.kind().name(),
                c.visibleDurationMs(), c.minVisibleRatioBps(), c.pageVisible())) == 1;
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
        return mapper.stored(id).stream().findFirst().map(row ->
                new Stored(row.hash(), new Result(receipt(row.receipt()), snapshot(row.snapshot(), subject))));
    }
    /** 原种类的canonical接受时间单独保存，重复回执不重测或修改该时间。 */
    public Receipt canonical(UUID id) {
        return mapper.canonical(id).stream().findFirst().map(ClickSettlementRepository::receipt).orElseThrow();
    }
    /** 首次决定写成后不可再修改；失败回滚同事务的计数/资金。 */
    public void complete(Receipt r) {
        requireOne(mapper.complete(new ClickSettlementMapper.Completed(r.receiptId(), r.status().name(), r.canonicalReceiptId(),
                timestamp(r.acceptedAt()), timestamp(r.decidedAt()))), "点击回执决定");
    }
    /** rate先于账户加锁；每用户一行，不积累逐分钟行，也不在DB故障时放行。 */
    public void lockRate(long userId) {
        mapper.ensureRate(userId);
        DataAccessUtils.requiredSingleResult(mapper.lockRate(userId));
    }
    /** 持有全部锁后才按本次决策分钟计数；超限不自增，原资格随后永久拒绝并释放。 */
    public boolean countClick(long userId,int limit,Instant now) {
        return mapper.countClick(userId, limit, timestamp(now)) == 1;
    }
    /** 单次状态变化携带全部条件；数据库禁止两条件齐备却仍HELD的中间状态。 */
    public void advance(Snapshot s, UUID visible, UUID click, UUID ledger, State state, CloseReason reason, Instant now) {
        requireOne(mapper.advance(new ClickSettlementMapper.Advance(s.prepared().qualificationId(), s.version(), visible, click, ledger,
                state.name(), reason == null ? null : reason.name(), state == State.HELD ? null : timestamp(now), timestamp(now))), "点击状态迁移");
    }
    /** 点击和CPM使用同一账户锁和条件更新；释放与捕获都减原日held，只有捕获增加charged。 */
    public long debitHeld(BudgetAccount a,java.math.BigDecimal amount,boolean capture,Instant now) {
        requireOne(mapper.debitHeld(a, amount, capture ? amount : java.math.BigDecimal.ZERO, timestamp(now)), "点击账户资金迁移");
        return a.version()+1;
    }
    /** 账本唯一约束覆盖资格和两种回执，任何冲突都回滚账户更新。 */
    public void appendLedger(UUID ledgerId,FrozenClickQuote q,Snapshot s,UUID visible,UUID click,long accountVersion,Instant now) {
        requireOne(mapper.appendLedger(new ClickSettlementMapper.Ledger(ledgerId, q.quoteId(), visible, click, q.operationId(),
                q.advertiserId(), q.adId(), q.creativeId(), q.itemId(), q.userId(), q.slot(), q.price().bidType().name(),
                s.prepared().budgetDate(), q.price().amount(), accountVersion, timestamp(now))), "点击账本");
    }
    /** 仅在原本地事务落可靠待办；broker投递不能占用资金事务。 */
    public void appendOutbox(CapturedEvent event) {
        String payload;
        try { payload=json.writeValueAsString(event); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("点击事件编码失败",ex); }
        requireOne(mapper.appendOutbox(new ClickSettlementMapper.Outbox(UUID.fromString(event.eventId()), event.eventType(), event.schemaVersion(),
                CapturedEvent.TOPIC, event.advertiserId()+":"+event.budgetDate(), payload)), "点击Outbox");
    }
    private static Snapshot snapshot(ClickSettlementMapper.SnapshotRow row, String subject) {
        if (!subject.equals(row.ownerSubject())) throw new Reservation.AccessDenied();
        var p = new Prepared(row.qualificationId(), Decision.valueOf(row.decision()), row.budgetDate(), row.amount().toPlainString(),
                instant(row.issuedAt()), instant(row.expiresAt()), instant(row.preparedAt()), row.qualificationToken());
        return new Snapshot(p, State.valueOf(row.state()), row.version(), row.visibleReceiptId(), row.clickReceiptId(), row.ledgerId(),
                row.capturedAmount() == null ? null : row.capturedAmount().toPlainString(), instant(row.closedAt()),
                row.closeReason() == null ? null : CloseReason.valueOf(row.closeReason()));
    }
    private static Receipt receipt(ClickSettlementMapper.ReceiptRow row) {
        if (UNFINISHED_RECEIPT_STATUS.equals(row.status())) throw new IllegalStateException("发现未完成点击回执");
        return new Receipt(row.receiptId(), row.qualificationId(), Kind.valueOf(row.kind()), ReceiptStatus.valueOf(row.status()),
                row.canonicalReceiptId(), instant(row.acceptedAt()), instant(row.decidedAt()));
    }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
    private static void requireOne(int rows,String effect) { if(rows!=1) throw new IllegalStateException(effect+"未生效"); }
    private record Stored(String hash,Result result) {}
}
