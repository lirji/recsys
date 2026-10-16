package com.recsys.ad.billing;

import com.recsys.common.ad.billing.BudgetAccount;
import com.recsys.common.ad.billing.Reservation;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/** 预占生命周期用例；统一按预占行→账户行加锁，调额只锁账户不会形成反向环。 */
public class ReservationService {
    private static final ZoneId BUDGET_ZONE = ZoneId.of("Asia/Shanghai");
    private final ReservationRepository repository;
    private final CpmQuoteRepository quotes;
    private final TransactionTemplate transactions;
    private final boolean admissionEnabled;

    public ReservationService(ReservationRepository repository, CpmQuoteRepository quotes,
                              TransactionTemplate transactions, boolean admissionEnabled) {
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != repository.dataSource() || quotes.dataSource() != repository.dataSource()) {
            throw new IllegalArgumentException("预占、报价和事务必须绑定同一个 adDb DataSource");
        }
        this.repository = repository;
        this.quotes = quotes;
        this.transactions = transactions;
        this.admissionEnabled = admissionEnabled;
    }

    /** owner 编排启动时验证各步骤使用相同物理资源。 */
    public javax.sql.DataSource dataSource() { return repository.dataSource(); }

    /** 首次准备结果不可变；查询当前状态需调用 find，重试不会重新占用或延长期限。 */
    public Reservation.Prepared prepare(UUID reservationId, UUID quoteId, String subjectKey) {
        validate(reservationId, subjectKey);
        if (quoteId == null) throw new IllegalArgumentException("quoteId 必填");
        return transactions.execute(tx -> {
            var previous = repository.find(reservationId, true);
            if (previous.isPresent()) return original(previous.get(), quoteId, subjectKey);
            if (!admissionEnabled) throw new IllegalStateException("新增预占已暂停，已有义务仍可查询或释放");
            var quote = quotes.find(quoteId).orElseThrow(() -> new IllegalArgumentException("服务端报价不存在"));
            Instant acceptedAt = repository.databaseNow();
            var day = acceptedAt.atZone(BUDGET_ZONE).toLocalDate();
            if (!repository.claim(reservationId, quote, subjectKey, day, acceptedAt)) {
                return original(repository.find(reservationId, true).orElseThrow(Reservation.Conflict::new), quoteId, subjectKey);
            }
            var account = repository.lockAccount(quote.advertiserId(), day);
            Reservation.Decision decision;
            // 等待账户锁期间也可能越过有效期，必须在真正增加 held 前再次看数据库时钟。
            if (!repository.databaseNow().isBefore(quote.expiresAt())) {
                decision = Reservation.Decision.QUOTE_EXPIRED;
            } else if (account.isEmpty()) {
                decision = Reservation.Decision.ACCOUNT_MISSING;
            } else if (account.get().availableAmount().compareTo(quote.price().amount()) < 0) {
                decision = Reservation.Decision.INSUFFICIENT_BUDGET;
            } else {
                if (!repository.hold(account.get(), quote.price().amount())) {
                    throw new IllegalStateException("已锁定账户的预占条件更新未生效 reservationId=" + reservationId);
                }
                decision = Reservation.Decision.HELD;
            }
            return repository.complete(reservationId, decision);
        });
    }

    /** 主动释放也服从到期状态，重复调用只返回已有终态。 */
    public Optional<Reservation> release(UUID reservationId, String subjectKey) {
        validate(reservationId, subjectKey);
        return transactions.execute(tx -> repository.find(reservationId, true).map(reservation -> {
            reservation.requireSubject(subjectKey);
            return closeHeld(reservation);
        }));
    }

    /** 返回当前状态并校验原主体，不把不命中解释为在途命令未执行。 */
    public Optional<Reservation> find(UUID reservationId, String subjectKey) {
        validate(reservationId, subjectKey);
        return repository.find(reservationId, false).map(reservation -> {
            reservation.requireSubject(subjectKey);
            return reservation;
        });
    }

    /** 一次短事务处理一笔到期待办；两个执行器通过行锁跳过对方已领取项。 */
    public boolean expireOne() {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            var due = repository.lockOneExpired();
            if (due.isEmpty()) return false;
            closeHeld(due.get());
            return true;
        }));
    }

    /** 仅恢复用例同库事务内调用；必须到期才释放，绝不借用主动release提前关闭。 */
    com.recsys.common.ad.billing.BillingRecovery.Status expireForRecovery(
            com.recsys.common.ad.billing.BillingReconciliation.Scope scope, UUID id) {
        BillingRecoveryService.requireTransaction(dataSource());
        var reservation = repository.find(id, true).orElse(null);
        if (reservation == null || reservation.prepared().advertiserId() != scope.advertiserId()
                || !reservation.prepared().budgetDate().equals(scope.budgetDate())) {
            return com.recsys.common.ad.billing.BillingRecovery.Status.NOT_FOUND;
        }
        if (reservation.state() != Reservation.State.HELD) return com.recsys.common.ad.billing.BillingRecovery.Status.ALREADY_TERMINAL;
        var account = repository.lockAccount(scope.advertiserId(), scope.budgetDate()).orElseThrow();
        var now = repository.databaseNow();
        if (now.isBefore(reservation.prepared().expiresAt())) return com.recsys.common.ad.billing.BillingRecovery.Status.NOT_ELIGIBLE;
        repository.close(reservation, account, now);
        return com.recsys.common.ad.billing.BillingRecovery.Status.EXPIRED;
    }

    private Reservation closeHeld(Reservation reservation) {
        if (reservation.state() != Reservation.State.HELD) return reservation;
        var prepared = reservation.prepared();
        BudgetAccount account = repository.lockAccount(prepared.advertiserId(), prepared.budgetDate())
                .orElseThrow(() -> new IllegalStateException("已占用预占缺少原预算日账户 reservationId=" + prepared.reservationId()));
        return repository.close(reservation, account, repository.databaseNow());
    }

    private static Reservation.Prepared original(Reservation reservation, UUID quoteId, String subject) {
        var prepared = reservation.prepared();
        if (!prepared.quoteId().equals(quoteId) || !prepared.subjectKey().equals(subject)) throw new Reservation.Conflict();
        return prepared;
    }

    private static void validate(UUID reservationId, String subject) {
        if (reservationId == null) throw new IllegalArgumentException("reservationId 必填");
        Reservation.validateSubject(subject);
    }
}
