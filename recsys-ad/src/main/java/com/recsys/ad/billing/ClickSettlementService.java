package com.recsys.ad.billing;

import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ClickSettlement.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** 点击资金唯一用例：quote→命令→用户计数→原日账户；两条件齐备后的所有效果只有一次提交。 */
public class ClickSettlementService {
    private static final ZoneId BUDGET_ZONE = ZoneId.of("Asia/Shanghai");
    private final ClickSettlementRepository repository;
    private final ClickQuoteRepository quotes;
    private final ReservationRepository accounts;
    private final DeliveryTokenCodec tokens;
    private final TransactionTemplate transactions;
    private final boolean preparationAdmission;
    private final boolean receiptAdmission;
    private final FraudPolicy fraud;

    /** 启动时复制成不可变策略，防止单笔事务中途读取到一半刷新后的配置。 */
    public record FraudPolicy(boolean enabled,int maxClicksPerMinute) {
        public FraudPolicy { if(maxClicksPerMinute<1) throw new IllegalArgumentException("点击每分钟阈值必须为正数"); }
    }
    public ClickSettlementService(ClickSettlementRepository repository,ClickQuoteRepository quotes,ReservationRepository accounts,
            DeliveryTokenCodec tokens,TransactionTemplate transactions,boolean preparationAdmission,boolean receiptAdmission,FraudPolicy fraud) {
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource()!=repository.dataSource() || quotes.dataSource()!=repository.dataSource()
                || accounts.dataSource()!=repository.dataSource()) throw new IllegalArgumentException("点击结算、报价、账户与事务必须同一adDb");
        this.repository=repository; this.quotes=quotes; this.accounts=accounts; this.tokens=tokens; this.transactions=transactions;
        this.preparationAdmission=preparationAdmission; this.receiptAdmission=receiptAdmission; this.fraud=Objects.requireNonNull(fraud);
    }

    /** 资格固定等于quoteId，重复准备只恢复不可变原结果，绝不重占或续期。 */
    public Prepared prepare(UUID id,String subject) {
        validate(id,subject);
        return transactions.execute(tx->{
            lockQuote(id);
            var q=quotes.find(id,subject).orElseThrow(SandboxDelivery.InvalidProof::new);
            var previous=repository.find(id,subject);
            if(previous.isPresent()) return previous.get().prepared();
            if(!preparationAdmission) throw new IllegalStateException("新增点击准备已暂停，已有义务仍可恢复或释放");
            var started=accounts.databaseNow(); var day=started.atZone(BUDGET_ZONE).toLocalDate();
            var account=accounts.lockAccount(q.advertiserId(),day);
            var now=accounts.databaseNow();
            Decision decision;
            if(!now.isBefore(q.expiresAt())) decision=Decision.QUOTE_EXPIRED;
            else if(account.isEmpty()) decision=Decision.ACCOUNT_MISSING;
            else if(account.get().availableAmount().compareTo(q.price().amount())<0) decision=Decision.INSUFFICIENT_BUDGET;
            else {
                if(!accounts.hold(account.get(),q.price().amount())) throw new IllegalStateException("点击预占条件更新未生效");
                decision=Decision.HELD;
            }
            String token=decision==Decision.HELD ? tokens.sign(ClickSettlement.TOKEN_PURPOSE,proof(q,day)) : null;
            return repository.insert(new Prepared(id,decision,day,q.price().amount().toPlainString(),q.issuedAt(),q.expiresAt(),started,token),subject).prepared();
        });
    }

    /** 先恢复同命令，再验签；未知结果必须原ID重试，关闭新增也不抹掉已提交结果。 */
    public Result accept(Command command) {
        var original=repository.original(command);
        if(original.isPresent()) return original.get();
        if(!receiptAdmission) throw new IllegalStateException("新增点击回执已暂停，原结果仍可查询或恢复");
        var signed=tokens.verify(command.qualificationToken(),ClickSettlement.TOKEN_PURPOSE,Proof.class);
        if(!command.subject().equals(signed.subject())) throw new Reservation.AccessDenied();
        return transactions.execute(tx->{
            lockQuote(signed.qualificationId());
            var q=quotes.find(signed.qualificationId(),command.subject()).orElseThrow(SandboxDelivery.InvalidProof::new);
            var s=repository.find(q.quoteId(),command.subject()).orElseThrow(SandboxDelivery.InvalidProof::new);
            if(!signed.equals(proof(q,s.prepared().budgetDate())) || s.prepared().decision()!=Decision.HELD
                    || !command.qualificationToken().equals(s.prepared().qualificationToken())) throw new SandboxDelivery.InvalidProof();
            if(!repository.claim(command,q.quoteId())) return repository.original(command).orElseThrow();
            var now=accounts.databaseNow();
            UUID canonical=command.kind()==Kind.VISIBLE?s.visibleReceiptId():s.clickReceiptId();
            if(canonical!=null) {
                var first=repository.canonical(canonical);
                return finish(command,q.quoteId(),ReceiptStatus.ALREADY_ACCEPTED,canonical,first.acceptedAt(),now);
            }
            if(s.state()!=State.HELD) {
                return finish(command,q.quoteId(),s.state()==State.EXPIRED?ReceiptStatus.EXPIRED:ReceiptStatus.NOT_HELD,null,null,now);
            }
            boolean countClick=command.kind()==Kind.CLICK && fraud.enabled();
            if(countClick) repository.lockRate(q.userId());
            var account=originalAccount(q,s);
            // 等待用户或账户锁可能跨分钟、跨日或越过期限；必须在所有锁拿到之后再决定。
            now=accounts.databaseNow();
            if(!now.isBefore(q.expiresAt())) {
                close(q,s,account,CloseReason.EXPIRED,now);
                return finish(command,q.quoteId(),ReceiptStatus.EXPIRED,null,null,now);
            }
            if(!command.meetsPolicy(q.issuedAt(),now)) return finish(command,q.quoteId(),ReceiptStatus.POLICY_NOT_MET,null,null,now);
            if(countClick && !repository.countClick(q.userId(),fraud.maxClicksPerMinute(),now)) {
                close(q,s,account,CloseReason.FRAUD_REJECTED,now);
                return finish(command,q.quoteId(),ReceiptStatus.FRAUD_REJECTED,null,null,now);
            }
            UUID visible=command.kind()==Kind.VISIBLE?command.receiptId():s.visibleReceiptId();
            UUID click=command.kind()==Kind.CLICK?command.receiptId():s.clickReceiptId();
            boolean capture=visible!=null && click!=null;
            if(capture) {
                var ledgerId=UUID.randomUUID();
                long version=repository.debitHeld(account,q.price().amount(),true,now);
                repository.appendLedger(ledgerId,q,s,visible,click,version,now);
                repository.appendOutbox(event(ledgerId,q,s,visible,click,version,now));
                repository.advance(s,visible,click,ledgerId,State.CAPTURED,CloseReason.CLICK_CAPTURED,now);
            } else repository.advance(s,visible,click,null,State.HELD,null,now);
            return finish(command,q.quoteId(),capture?ReceiptStatus.ACCEPTED_CAPTURED:ReceiptStatus.ACCEPTED_WAITING,command.receiptId(),now,now);
        });
    }

    /** 原回执决定和当前结算状态一起恢复，不把WAITING决定误判成永远未捕获。 */
    public Optional<Result> findReceipt(UUID id,String subject) { validate(id,subject); return repository.findReceipt(id,subject); }
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
    private Result finish(Command c,UUID id,ReceiptStatus status,UUID canonical,Instant accepted,Instant now) {
        repository.complete(new Receipt(c.receiptId(),id,c.kind(),status,canonical,accepted,now));
        return repository.findReceipt(c.receiptId(),c.subject()).orElseThrow();
    }
    private BudgetAccount originalAccount(FrozenClickQuote q,Snapshot s) {
        return accounts.lockAccount(q.advertiserId(),s.prepared().budgetDate()).orElseThrow(()->new IllegalStateException("点击预占原账户不存在"));
    }
    private void close(FrozenClickQuote q,Snapshot s,BudgetAccount account,CloseReason reason,Instant now) {
        repository.debitHeld(account,q.price().amount(),false,now);
        repository.advance(s,s.visibleReceiptId(),s.clickReceiptId(),null,reason==CloseReason.EXPIRED?State.EXPIRED:State.RELEASED,reason,now);
    }
    private void lockQuote(UUID id) { if(!repository.lockQuote(id)) throw new SandboxDelivery.InvalidProof(); }
    private static void validate(UUID id,String subject) { Objects.requireNonNull(id); Reservation.validateSubject(subject); }
    private static Proof proof(FrozenClickQuote q,LocalDate day) { return new Proof(q.quoteId(),q.subject(),ClickQuoteRepository.fingerprint(q),day,ClickSettlement.POLICY); }
    private static CapturedEvent event(UUID ledger,FrozenClickQuote q,Snapshot s,UUID visible,UUID click,long version,Instant now) {
        return new CapturedEvent(ledger.toString(),CapturedEvent.TYPE,CapturedEvent.SCHEMA_VERSION,BudgetAccount.TRAFFIC_CLASS,CnyAmount.CURRENCY,
                q.price().bidType().name(),ClickPrice.UNIT,ClickPrice.VERSION,q.operationId().toString(),q.quoteId().toString(),visible.toString(),click.toString(),
                Long.toString(q.advertiserId()),Long.toString(q.adId()),Long.toString(q.creativeId()),Long.toString(q.itemId()),Long.toString(q.userId()),
                q.slot(),s.prepared().budgetDate().toString(),q.price().amount().toPlainString(),version,now);
    }
}
