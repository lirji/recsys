package com.recsys.ad.billing;

import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ConversionSettlement.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** CPA资金唯一用例：quote→命令→原日账户；两条件齐备后的所有效果只有一次提交。 */
public class ConversionSettlementService {
    private static final ZoneId BUDGET_ZONE = ZoneId.of("Asia/Shanghai");
    private final ConversionSettlementRepository repository;
    private final ConversionQuoteRepository quotes;
    private final ReservationRepository accounts;
    private final DeliveryTokenCodec tokens;
    private final TransactionTemplate transactions;
    private final boolean preparationAdmission;
    private final boolean receiptAdmission;

    public ConversionSettlementService(ConversionSettlementRepository repository,ConversionQuoteRepository quotes,ReservationRepository accounts,
            DeliveryTokenCodec tokens,TransactionTemplate transactions,boolean preparationAdmission,boolean receiptAdmission) {
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource()!=repository.dataSource() || quotes.dataSource()!=repository.dataSource()
                || accounts.dataSource()!=repository.dataSource()) throw new IllegalArgumentException("CPA结算、报价、账户与事务必须同一adDb");
        this.repository=repository; this.quotes=quotes; this.accounts=accounts; this.tokens=tokens; this.transactions=transactions;
        this.preparationAdmission=preparationAdmission; this.receiptAdmission=receiptAdmission;
    }

    /** 准备编排校验资源身份，不能把相同URL当作同一事务资源。 */
    public javax.sql.DataSource dataSource() { return repository.dataSource(); }

    /** 资格固定等于quoteId，重复准备只恢复不可变原结果，绝不重占或续期。 */
    public Prepared prepare(UUID id,String subject) {
        validate(id,subject);
        return transactions.execute(tx->{
            lockQuote(id);
            var q=quotes.find(id,subject).orElseThrow(SandboxDelivery.InvalidProof::new);
            var previous=repository.find(id,subject);
            if(previous.isPresent()) return previous.get().prepared();
            if(!preparationAdmission) throw new IllegalStateException("新增CPA准备已暂停，已有义务仍可恢复或释放");
            var started=accounts.databaseNow(); var day=started.atZone(BUDGET_ZONE).toLocalDate();
            var account=accounts.lockAccount(q.advertiserId(),day);
            var now=accounts.databaseNow();
            Decision decision;
            if(!now.isBefore(q.expiresAt())) decision=Decision.QUOTE_EXPIRED;
            else if(account.isEmpty()) decision=Decision.ACCOUNT_MISSING;
            else if(account.get().availableAmount().compareTo(q.price().amount())<0) decision=Decision.INSUFFICIENT_BUDGET;
            else {
                if(!accounts.hold(account.get(),q.price().amount())) throw new IllegalStateException("CPA预占条件更新未生效");
                decision=Decision.HELD;
            }
            String token=decision==Decision.HELD ? tokens.sign(ConversionSettlement.TOKEN_PURPOSE,proof(q,day)) : null;
            return repository.insert(new Prepared(id,decision,day,q.price().amount().toPlainString(),q.issuedAt(),q.expiresAt(),started,token),subject,q.advertiserId()).prepared();
        });
    }

    /** 可见主体仅绑定原展示用户；原命令可在暂停或旧key退出后恢复。 */
    public VisibleResult acceptVisible(VisibleCommand command) {
        var original=repository.originalVisible(command); if(original.isPresent()) return original.get();
        admit();
        var signed=tokens.verify(command.qualificationToken(),ConversionSettlement.TOKEN_PURPOSE,Proof.class);
        if(!command.subject().equals(signed.subject())) throw new Reservation.AccessDenied();
        return transactions.execute(tx->{
            lockQuote(signed.qualificationId());
            var q=qualified(signed,command.qualificationToken());
            var s=repository.find(q.quoteId(),command.subject()).orElseThrow();
            if(!repository.claimVisible(command,q.quoteId())) return repository.originalVisible(command).orElseThrow();
            var now=accounts.databaseNow();
            if(s.visibleReceiptId()!=null) {
                var first=repository.canonicalVisible(s.visibleReceiptId());
                return finishVisible(command,q.quoteId(),VisibleStatus.ALREADY_ACCEPTED,first.receiptId(),first.acceptedAt(),now);
            }
            if(s.state()!=State.HELD) return finishVisible(command,q.quoteId(),s.state()==State.EXPIRED?VisibleStatus.EXPIRED:VisibleStatus.NOT_HELD,null,null,now);
            var account=originalAccount(q,s); now=accounts.databaseNow();
            if(!now.isBefore(q.expiresAt())) {
                close(q,s,account,CloseReason.EXPIRED,now);
                return finishVisible(command,q.quoteId(),VisibleStatus.EXPIRED,null,null,now);
            }
            if(!command.meetsPolicy(q.issuedAt(),now)) return finishVisible(command,q.quoteId(),VisibleStatus.POLICY_NOT_MET,null,null,now);
            boolean capture=s.outcomeId()!=null;
            if(capture) capture(q,s,account,command.receiptId(),s.outcomeId(),now);
            else repository.advance(s,command.receiptId(),null,null,State.HELD,null,now);
            return finishVisible(command,q.quoteId(),capture?VisibleStatus.ACCEPTED_CAPTURED:VisibleStatus.ACCEPTED_WAITING,command.receiptId(),now,now);
        });
    }
    /** 仅受信边界在完成当前广告主授权后调用；本owner校验资格归属，绝不把展示subject当作回调actor。 */
    public OutcomeResult acceptAuthorizedConversion(ConversionCommand command) {
        var original=repository.originalOutcome(command); if(original.isPresent()) return original.get();
        admit();
        var signed=tokens.verify(command.qualificationToken(),ConversionSettlement.TOKEN_PURPOSE,Proof.class);
        return transactions.execute(tx->{
            lockQuote(signed.qualificationId());
            var q=qualified(signed,command.qualificationToken());
            if(q.advertiserId()!=command.advertiserId()) throw new Reservation.AccessDenied();
            var s=repository.findForAdvertiser(q.quoteId(),command.advertiserId()).orElseThrow();
            UUID id=UUID.randomUUID();
            if(!repository.claimOutcome(id,command,q.quoteId())) return repository.originalOutcome(command).orElseThrow();
            var now=accounts.databaseNow();
            if(s.outcomeId()!=null) return finishOutcome(command,id,OutcomeStatus.DUPLICATE_QUALIFICATION,s.outcomeId(),now);
            if(s.state()!=State.HELD) return finishOutcome(command,id,s.state()==State.EXPIRED?OutcomeStatus.EXPIRED:OutcomeStatus.NOT_HELD,null,now);
            var account=originalAccount(q,s); now=accounts.databaseNow();
            // 账户等待可能越过五分钟，数据库接收决定时间必须在全部锁就绪后重取。
            if(!now.isBefore(q.expiresAt())) {
                close(q,s,account,CloseReason.EXPIRED,now);
                return finishOutcome(command,id,OutcomeStatus.EXPIRED,null,now);
            }
            if(command.occurredAt().isBefore(q.issuedAt()) || !command.occurredAt().isBefore(q.expiresAt()) || command.occurredAt().isAfter(now)) {
                return finishOutcome(command,id,OutcomeStatus.OUTSIDE_ATTRIBUTION,null,now);
            }
            boolean capture=s.visibleReceiptId()!=null;
            repository.completeOutcome(id,capture?OutcomeStatus.ACCEPTED_CAPTURED:OutcomeStatus.ACCEPTED_WAITING,id,now);
            if(capture) capture(q,s,account,s.visibleReceiptId(),id,now);
            else repository.advance(s,null,id,null,State.HELD,null,now);
            return repository.findOutcome(command.advertiserId(),command.eventId()).orElseThrow();
        });
    }
    /** 查询广告主事件前，外部边界每次重新授权；内部完整快照由外部适配层脱敏后返回。 */
    public Optional<OutcomeResult> findAuthorizedOutcome(long advertiserId,UUID eventId) {
        if(advertiserId<=0 || eventId==null) throw new IllegalArgumentException("CPA查询作用域非法");
        return repository.findOutcome(advertiserId,eventId);
    }
    /** 原回执决定与当前状态同快照返回。 */
    public Optional<VisibleResult> findVisible(UUID id,String subject) { validate(id,subject);return repository.findVisible(id,subject); }
    /** 只读当前状态，包括被释放后的原准备结果。 */
    public Optional<Snapshot> find(UUID id,String subject) { validate(id,subject); return repository.find(id,subject); }
    /** 主动释放不撤销已捕获效果；到期后释放统一记EXPIRED，拒绝后补扣。 */
    public Optional<Snapshot> release(UUID id,String subject) {
        validate(id,subject);
        return transactions.execute(tx->{
            if(!repository.lockQuote(id)) return Optional.empty();
            var q=quotes.find(id,subject).orElseThrow();
            return repository.find(id,subject).map(s->{
                if(s.state()==State.HELD) {
                    var account=originalAccount(q,s); var now=accounts.databaseNow();
                    close(q,s,account,now.isBefore(q.expiresAt())?CloseReason.DELIVERY_ABORTED:CloseReason.EXPIRED,now);
                }
                return repository.find(id,subject).orElseThrow();
            });
        });
    }
    /** 恢复命令和到期效果共用同库事务；quote→账户锁序与原捕获路径一致。 */
    BillingRecovery.Status expireForRecovery(BillingReconciliation.Scope scope, UUID id) {
        BillingRecoveryService.requireTransaction(dataSource());
        if (!repository.lockQuote(id)) return BillingRecovery.Status.NOT_FOUND;
        var subject = repository.owner(id);
        var quote = quotes.find(id, subject).orElseThrow();
        var current = repository.find(id, subject).orElse(null);
        if (current == null || quote.advertiserId() != scope.advertiserId()
                || !current.prepared().budgetDate().equals(scope.budgetDate())) return BillingRecovery.Status.NOT_FOUND;
        if (current.state() != State.HELD) return BillingRecovery.Status.ALREADY_TERMINAL;
        var account = originalAccount(quote, current);
        var now = accounts.databaseNow();
        if (now.isBefore(quote.expiresAt())) return BillingRecovery.Status.NOT_ELIGIBLE;
        close(quote, current, account, CloseReason.EXPIRED, now);
        return BillingRecovery.Status.EXPIRED;
    }
    /** 单笔短事务回收；多执行器通过同序quote行锁SKIP LOCKED分工。 */
    public boolean expireOne() {
        return Boolean.TRUE.equals(transactions.execute(tx->{
            var id=repository.lockOneExpired(); if(id.isEmpty()) return false;
            var subject=repository.owner(id.get());
            var q=quotes.find(id.get(),subject).orElseThrow(); var s=repository.find(id.get(),subject).orElseThrow();
            // READ_COMMITTED等待后再次检查终态，已捕获/已释放不能再次减held。
            if(s.state()!=State.HELD) return true;
            var account=originalAccount(q,s); var now=accounts.databaseNow();
            if(now.isBefore(q.expiresAt())) throw new IllegalStateException("到期扫描返回未到期资格");
            close(q,s,account,CloseReason.EXPIRED,now); return true;
        }));
    }
    private void admit() { if(!receiptAdmission) throw new IllegalStateException("新增CPA事件已暂停，原结果仍可恢复"); }
    private FrozenConversionQuote qualified(Proof signed,String token) {
        var q=quotes.find(signed.qualificationId(),signed.subject()).orElseThrow(SandboxDelivery.InvalidProof::new);
        var s=repository.find(q.quoteId(),signed.subject()).orElseThrow(SandboxDelivery.InvalidProof::new);
        if(s.prepared().decision()!=Decision.HELD || !signed.equals(proof(q,s.prepared().budgetDate()))
                || !token.equals(s.prepared().qualificationToken())) throw new SandboxDelivery.InvalidProof();
        return q;
    }
    private VisibleResult finishVisible(VisibleCommand c,UUID qualification,VisibleStatus status,UUID canonical,Instant accepted,Instant now) {
        repository.completeVisible(new VisibleReceipt(c.receiptId(),qualification,status,canonical,accepted,now));
        return repository.findVisible(c.receiptId(),c.subject()).orElseThrow();
    }
    private OutcomeResult finishOutcome(ConversionCommand c,UUID id,OutcomeStatus status,UUID canonical,Instant now) {
        repository.completeOutcome(id,status,canonical,now);return repository.findOutcome(c.advertiserId(),c.eventId()).orElseThrow();
    }
    private void capture(FrozenConversionQuote q,Snapshot s,BudgetAccount account,UUID visible,UUID outcome,Instant now) {
        var ledger=UUID.randomUUID();long version=repository.debitHeld(account,q.price().amount(),true,now);
        repository.appendLedger(ledger,q,s,visible,outcome,version,now);
        repository.appendOutbox(event(ledger,q,s,visible,repository.outcome(outcome),version,now));
        repository.advance(s,visible,outcome,ledger,State.CAPTURED,CloseReason.CONVERSION_CAPTURED,now);
    }
    private BudgetAccount originalAccount(FrozenConversionQuote q,Snapshot s) {
        return accounts.lockAccount(q.advertiserId(),s.prepared().budgetDate()).orElseThrow(()->new IllegalStateException("CPA预占原账户不存在"));
    }
    private void close(FrozenConversionQuote q,Snapshot s,BudgetAccount account,CloseReason reason,Instant now) {
        repository.debitHeld(account,q.price().amount(),false,now);
        repository.advance(s,s.visibleReceiptId(),s.outcomeId(),null,reason==CloseReason.EXPIRED?State.EXPIRED:State.RELEASED,reason,now);
    }
    private void lockQuote(UUID id) { if(!repository.lockQuote(id)) throw new SandboxDelivery.InvalidProof(); }
    private static void validate(UUID id,String subject) { Objects.requireNonNull(id); Reservation.validateSubject(subject); }
    private static Proof proof(FrozenConversionQuote q,LocalDate day) { return new Proof(q.quoteId(),q.subject(),ConversionQuoteRepository.fingerprint(q),day,ConversionSettlement.POLICY); }
    private static CapturedEvent event(UUID ledger,FrozenConversionQuote q,Snapshot s,UUID visible,Outcome outcome,long version,Instant now) {
        return new CapturedEvent(ledger.toString(),CapturedEvent.TYPE,CapturedEvent.SCHEMA_VERSION,BudgetAccount.TRAFFIC_CLASS,CnyAmount.CURRENCY,
                q.price().bidType().name(),ConversionPrice.UNIT,ConversionPrice.VERSION,q.operationId().toString(),q.quoteId().toString(),visible.toString(),
                outcome.outcomeId().toString(),outcome.eventId().toString(),Long.toString(q.advertiserId()),Long.toString(q.adId()),Long.toString(q.creativeId()),
                Long.toString(q.itemId()),Long.toString(q.userId()),q.slot(),s.prepared().budgetDate().toString(),q.price().amount().toPlainString(),version,outcome.occurredAt(),now);
    }
}
