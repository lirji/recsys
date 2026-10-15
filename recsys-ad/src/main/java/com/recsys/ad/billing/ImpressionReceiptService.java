package com.recsys.ad.billing;

import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ImpressionReceipt.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.ad.delivery.SandboxDelivery.Qualification;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 可见回执唯一资金用例；原结果、捕获、账本和 Outbox 只有一个本地事务提交点。 */
public class ImpressionReceiptService {
    private final ImpressionReceiptRepository receipts;
    private final PreparationRepository preparations;
    private final ReservationRepository reservations;
    private final CpmQuoteRepository quotes;
    private final DeliveryTokenCodec tokens;
    private final TransactionTemplate transactions;
    private final boolean admissionEnabled;
    public ImpressionReceiptService(ImpressionReceiptRepository receipts, PreparationRepository preparations,
            ReservationRepository reservations, CpmQuoteRepository quotes, DeliveryTokenCodec tokens, TransactionTemplate transactions, boolean admissionEnabled) {
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != receipts.dataSource() || receipts.dataSource() != preparations.dataSource()
                || receipts.dataSource() != reservations.dataSource() || receipts.dataSource() != quotes.dataSource()) {
            throw new IllegalArgumentException("回执、准备、预占、报价与事务必须同一 adDb");
        }
        this.receipts=receipts; this.preparations=preparations; this.reservations=reservations; this.quotes=quotes;
        this.tokens=tokens; this.transactions=transactions; this.admissionEnabled=admissionEnabled;
    }

    /** 已提交同 ID 同负载直接返回原结果；尚未提交的竞争通过数据库唯一键串行化。 */
    public Result accept(Command command) {
        var original = receipts.original(command);
        if (original.isPresent()) return original.get();
        if (!admissionEnabled) throw new IllegalStateException("新回执接受已暂停，已有结果仍可查询或恢复");
        var qualification = tokens.verify(command.qualificationToken(), "qualification", Qualification.class);
        if (!command.subject().equals(qualification.subject())) throw new Reservation.AccessDenied();
        var prepared = preparations.find(qualification.childId(), command.subject()).orElseThrow(SandboxDelivery.InvalidProof::new);
        if (prepared.status() != SandboxDelivery.Status.QUALIFIED || !prepared.qualificationToken().equals(command.qualificationToken())) {
            throw new SandboxDelivery.InvalidProof();
        }
        return transactions.execute(tx -> {
            if (!receipts.claim(command, qualification.childId())) return receipts.original(command).orElseThrow();
            var reservation = reservations.find(qualification.childId(), true).orElseThrow(SandboxDelivery.InvalidProof::new);
            reservation.requireSubject(command.subject());
            var quote = quotes.find(qualification.childId()).orElseThrow(SandboxDelivery.InvalidProof::new);
            validateBinding(qualification, reservation, quote);
            var now = reservations.databaseNow();
            var previousCapture = receipts.alreadyCaptured(qualification.childId(), command.receiptId(), now);
            if (previousCapture.isPresent()) {
                var fact = previousCapture.get();
                if (reservation.state() != Reservation.State.CAPTURED
                        || CnyAmount.parse(fact.chargedAmount()).compareTo(reservation.prepared().amount()) != 0
                        || !fact.budgetDate().equals(reservation.prepared().budgetDate())) {
                    throw new IllegalStateException("账本与捕获预占不一致，必须先对账");
                }
                return receipts.complete(fact);
            }
            if (reservation.state() == Reservation.State.CAPTURED) throw new IllegalStateException("捕获预占缺少账本事实");
            if (!now.isBefore(qualification.expiresAt())) return reject(command, qualification, Status.EXPIRED, now);
            if (reservation.state() != Reservation.State.HELD) return reject(command, qualification, Status.NOT_HELD, now);
            var held = reservation.prepared();
            var account = reservations.lockAccount(held.advertiserId(), held.budgetDate())
                    .orElseThrow(() -> new IllegalStateException("预占原预算日账户不存在"));
            // 账户锁等待可能越过期限，接受时间必须在锁拿到后重新测量。
            now = reservations.databaseNow();
            if (!now.isBefore(qualification.expiresAt())) return reject(command, qualification, Status.EXPIRED, now);
            if (!command.meetsPolicy(qualification.issuedAt(), now)) return reject(command, qualification, Status.POLICY_NOT_MET, now);
            var captured = reservations.capture(reservation, account, now);
            UUID ledgerId = UUID.randomUUID();
            receipts.appendLedger(ledgerId, command, qualification, reservation, captured.version(), now);
            var event = new CapturedEvent(ledgerId.toString(), CapturedEvent.TYPE, CapturedEvent.SCHEMA_VERSION, BudgetAccount.TRAFFIC_CLASS, CnyAmount.CURRENCY,
                    CpmPrice.UNIT, CpmPrice.VERSION, qualification.operationId().toString(), qualification.childId().toString(), command.receiptId().toString(),
                    Long.toString(held.advertiserId()), Long.toString(held.adId()), Long.toString(qualification.creativeId()), Long.toString(qualification.userId()),
                    qualification.slot(), held.budgetDate().toString(), held.amount().toPlainString(), captured.version(), now);
            receipts.appendOutbox(event);
            return receipts.complete(new Result(command.receiptId(), qualification.childId(), Status.ACCEPTED, ledgerId, command.receiptId(),
                    held.amount().toPlainString(), held.budgetDate(), now, now));
        });
    }

    /** 只读原业务结果，不把查无结果当作可更换幂等键。 */
    public Optional<Result> find(UUID id, String subject) {
        if (id == null) throw new IllegalArgumentException("receiptId 必填");
        return receipts.find(id, subject);
    }
    private Result reject(Command command, Qualification qualification, Status status, Instant now) {
        return receipts.complete(new Result(command.receiptId(), qualification.childId(), status, null, null, "0.000000", null, null, now));
    }
    private void validateBinding(Qualification q, Reservation reservation, FrozenCpmQuote quote) {
        var held = reservation.prepared();
        if (!q.operationId().equals(quote.deliveryId()) || q.slot()!=quote.slot() || q.advertiserId()!=held.advertiserId()
                || q.adId()!=held.adId() || CnyAmount.parse(q.amount()).compareTo(held.amount())!=0
                || held.amount().compareTo(quote.price().amount())!=0 || !q.expiresAt().equals(held.expiresAt())
                || !q.expiresAt().equals(quote.expiresAt()) || !q.issuedAt().equals(quote.issuedAt())
                || !BudgetAccount.TRAFFIC_CLASS.equals(q.trafficClass()) || !CnyAmount.CURRENCY.equals(q.currency())
                || !SandboxDelivery.POLICY.equals(q.policy())) throw new SandboxDelivery.InvalidProof();
    }
}
