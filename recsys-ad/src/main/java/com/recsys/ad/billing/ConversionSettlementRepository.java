package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ConversionSettlement.*;
import com.recsys.ad.persistence.mapper.addb.ConversionSettlementMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** CPA结算所有SQL归同一adDb；写方法仅由持有quote父锁的短事务调用。 */
public class ConversionSettlementRepository {
    public static final String MIGRATION = "db/ad-billing/V013__conversion_settlement.sql";
    /** 两类事实的PENDING均只存在事务内部，不扩展公开决定枚举。 */
    private static final String UNFINISHED_DECISION_STATUS = "PENDING";
    private final ConversionSettlementMapper mapper;
    private final DataSource dataSource;
    private final ObjectMapper json;
    /** 实际会话工厂提供Mapper与资源身份，XML逐语句保留5秒查询超时。 */
    public ConversionSettlementRepository(SqlSessionTemplate sessions, ObjectMapper json) {
        this.mapper = sessions.getMapper(ConversionSettlementMapper.class);
        this.dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
        this.json = json.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    /** 事务必须绑定同一个连接池，而非仅有相同URL。 */
    public javax.sql.DataSource dataSource() { return dataSource; }

    /** 先扩展后启用，启动阶段只核对迁移，不自动修改数据库。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes()))) {
            throw new IllegalStateException("CPA结算V013缺失或摘要漂移");
        }
        mapper.verifySettlement(); mapper.verifyVisible(); mapper.verifyOutcome(); mapper.verifyLedger(); mapper.verifyOutbox();
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
    public Snapshot insert(Prepared p, String subject,long advertiserId) {
        requireOne(mapper.insert(new ConversionSettlementMapper.Preparation(p.qualificationId(), advertiserId, p.budgetDate(), p.decision().name(),
                p.qualificationToken(), timestamp(p.preparedAt()), p.decision() == Decision.HELD ? State.HELD.name() : State.REJECTED.name())), "CPA准备");
        return find(p.qualificationId(),subject).orElseThrow();
    }
    /** 广告主范围由已授权边界提供；条件在SQL中执行，不向回调方暴露展示主体。 */
    public Optional<Snapshot> findForAdvertiser(UUID id,long advertiserId) {
        return mapper.findForAdvertiser(id, advertiserId).stream().findFirst().map(row -> snapshot(row, row.ownerSubject()));
    }
    /** 唯一回执键先领取；冲突等待后恢复原完整请求。 */
    public boolean claimVisible(VisibleCommand c,UUID qualificationId) {
        return mapper.claimVisible(new ConversionSettlementMapper.VisibleClaim(c.receiptId(), qualificationId, c.subject(), c.payloadHash(),
                c.visibleDurationMs(), c.minVisibleRatioBps(), c.pageVisible())) == 1;
    }
    /** 原决定优先恢复，已保存命令不依赖当前验签key。 */
    public Optional<VisibleResult> originalVisible(VisibleCommand c) {
        return storedVisible(c.receiptId(),c.subject()).map(s->{if(!s.hash().equals(c.payloadHash()))throw new ConversionSettlement.Conflict();return s.result();});
    }
    /** 可见结果访问仍受原展示主体约束。 */
    public Optional<VisibleResult> findVisible(UUID id,String subject) { return storedVisible(id,subject).map(StoredVisible::result); }
    private Optional<StoredVisible> storedVisible(UUID id,String subject) {
        Reservation.validateSubject(subject);
        return mapper.storedVisible(id).stream().findFirst().map(row ->
                new StoredVisible(row.hash(), new VisibleResult(visible(row.visible()), snapshot(row.snapshot(), subject))));
    }
    /** 原可见条件时间不因后来重试改变。 */
    public VisibleReceipt canonicalVisible(UUID id) {
        return mapper.canonicalVisible(id).stream().findFirst().map(ConversionSettlementRepository::visible).orElseThrow();
    }
    /** 首次可见决定与资金效果同事务。 */
    public void completeVisible(VisibleReceipt r) {
        requireOne(mapper.completeVisible(new ConversionSettlementMapper.CompletedVisible(r.receiptId(), r.status().name(), r.canonicalReceiptId(),
                timestamp(r.acceptedAt()), timestamp(r.decidedAt()))), "CPA可见决定");
    }
    /** 内部事实ID独立于广告主提供的eventId；广告主+业务键由数据库唯一约束兜底。 */
    public boolean claimOutcome(UUID outcomeId,ConversionCommand c,UUID qualificationId) {
        return mapper.claimOutcome(new ConversionSettlementMapper.OutcomeClaim(outcomeId, c.advertiserId(), c.eventId(), qualificationId,
                c.actor(), c.payloadHash(), timestamp(c.occurredAt()))) == 1;
    }
    /** 同业务内容可由当前有权的另一个主体恢复，原actor只留作审计。 */
    public Optional<OutcomeResult> originalOutcome(ConversionCommand c) {
        return storedOutcome(c.advertiserId(),c.eventId()).map(s->{if(!s.hash().equals(c.payloadHash()))throw new ConversionSettlement.Conflict();return s.result();});
    }
    /** 同一SQL快照读取事实和当前结算，advertiser过滤不得遗漏。 */
    public Optional<OutcomeResult> findOutcome(long advertiserId,UUID eventId) { return storedOutcome(advertiserId,eventId).map(StoredOutcome::result); }
    private Optional<StoredOutcome> storedOutcome(long advertiserId,UUID eventId) {
        return mapper.storedOutcome(advertiserId, eventId).stream().findFirst().map(row ->
                new StoredOutcome(row.hash(), new OutcomeResult(outcome(row.outcome()), snapshot(row.snapshot(), row.snapshot().ownerSubject()))));
    }
    /** 持有quote锁后获取canonical业务事实；仅内部捕获用于构造事件。 */
    public Outcome outcome(UUID id) {
        return mapper.outcome(id).stream().findFirst().map(ConversionSettlementRepository::outcome).orElseThrow();
    }
    /** 第一决定只写一次；新事实尚PENDING时可以先完成决定再构造捕获Outbox，仍在同事务。 */
    public void completeOutcome(UUID id,OutcomeStatus status,UUID canonical,Instant now) {
        requireOne(mapper.completeOutcome(id, status.name(), canonical, timestamp(now)), "CPA业务事实决定");
    }
    /** 单次状态变化携带全部条件；数据库禁止两条件齐备却仍HELD的中间状态。 */
    public void advance(Snapshot s, UUID visible, UUID outcome, UUID ledger, State state, CloseReason reason, Instant now) {
        requireOne(mapper.advance(new ConversionSettlementMapper.Advance(s.prepared().qualificationId(), s.version(), visible, outcome, ledger,
                state.name(), reason == null ? null : reason.name(), state == State.HELD ? null : timestamp(now), timestamp(now))), "CPA状态迁移");
    }
    /** CPA和CPM使用同一账户锁和条件更新；释放与捕获都减原日held，只有捕获增加charged。 */
    public long debitHeld(BudgetAccount a,java.math.BigDecimal amount,boolean capture,Instant now) {
        requireOne(mapper.debitHeld(a, amount, capture ? amount : java.math.BigDecimal.ZERO, timestamp(now)), "CPA账户资金迁移");
        return a.version()+1;
    }
    /** 账本唯一约束覆盖资格和两种回执，任何冲突都回滚账户更新。 */
    public void appendLedger(UUID ledgerId,FrozenConversionQuote q,Snapshot s,UUID visible,UUID outcome,long accountVersion,Instant now) {
        requireOne(mapper.appendLedger(new ConversionSettlementMapper.Ledger(ledgerId, q.quoteId(), visible, outcome, q.operationId(),
                q.advertiserId(), q.adId(), q.creativeId(), q.itemId(), q.userId(), q.slot(), q.price().bidType().name(),
                s.prepared().budgetDate(), q.price().amount(), accountVersion, timestamp(now))), "CPA账本");
    }
    /** 仅在原本地事务落可靠待办；broker投递不能占用资金事务。 */
    public void appendOutbox(CapturedEvent event) {
        String payload;
        try { payload=json.writeValueAsString(event); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("CPA事件编码失败",ex); }
        requireOne(mapper.appendOutbox(new ConversionSettlementMapper.Outbox(UUID.fromString(event.eventId()), event.eventType(), event.schemaVersion(),
                CapturedEvent.TOPIC, event.advertiserId()+":"+event.budgetDate(), payload)), "CPAOutbox");
    }
    private static Snapshot snapshot(ConversionSettlementMapper.SnapshotRow row, String subject) {
        if (!subject.equals(row.ownerSubject())) throw new Reservation.AccessDenied();
        var p = new Prepared(row.qualificationId(), Decision.valueOf(row.decision()), row.budgetDate(), row.amount().toPlainString(),
                instant(row.issuedAt()), instant(row.expiresAt()), instant(row.preparedAt()), row.qualificationToken());
        return new Snapshot(p, State.valueOf(row.state()), row.version(), row.visibleReceiptId(), row.outcomeId(), row.ledgerId(),
                row.capturedAmount() == null ? null : row.capturedAmount().toPlainString(), instant(row.closedAt()),
                row.closeReason() == null ? null : CloseReason.valueOf(row.closeReason()));
    }
    private static VisibleReceipt visible(ConversionSettlementMapper.VisibleRow row) {
        if (UNFINISHED_DECISION_STATUS.equals(row.status())) throw new IllegalStateException("发现未完成CPA可见决定");
        return new VisibleReceipt(row.receiptId(), row.qualificationId(), VisibleStatus.valueOf(row.status()), row.canonicalReceiptId(),
                instant(row.acceptedAt()), instant(row.decidedAt()));
    }
    private static Outcome outcome(ConversionSettlementMapper.OutcomeRow row) {
        if (UNFINISHED_DECISION_STATUS.equals(row.status())) throw new IllegalStateException("发现未完成CPA业务决定");
        return new Outcome(row.outcomeId(), row.advertiserId(), row.eventId(), row.qualificationId(), instant(row.occurredAt()),
                instant(row.receivedAt()), OutcomeStatus.valueOf(row.status()), row.canonicalOutcomeId(), instant(row.decidedAt()));
    }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
    private static void requireOne(int rows,String effect) { if(rows!=1) throw new IllegalStateException(effect+"未生效"); }
    private record StoredVisible(String hash,VisibleResult result) {}
    private record StoredOutcome(String hash,OutcomeResult result) {}
}
