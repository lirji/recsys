package com.recsys.advertiser.billing;

import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.BillingReconciliation.*;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import java.time.Instant;
import java.util.List;

/** 浏览器契约不暴露内部BigDecimal/long；零金额、缺失账户与未知状态分别保留。 */
public record SandboxReconciliationView(int schemaVersion, String trafficClass, String currency, ScopeView scope,
        String observedAt, AccountView account, FundsStatus fundsStatus, String heldDelta, String chargedDelta, List<FamilyView> families) {
    /** 所有字段来自同一次owner快照，不能在管理面拼装多个不一致查询。 */
    public static SandboxReconciliationView from(Snapshot s) {
        return new SandboxReconciliationView(1, BudgetAccount.TRAFFIC_CLASS, CnyAmount.CURRENCY,
                new ScopeView(Long.toString(s.scope().advertiserId()), s.scope().budgetDate().toString()), s.observedAt().toString(),
                s.account() == null ? null : AccountView.from(s.account()), s.fundsStatus(), s.heldDelta(), s.chargedDelta(), s.families().stream().map(FamilyView::from).toList());
    }
    /** 广告主ID必须保持完整十进制字符串。 */
    public record ScopeView(String advertiserId, String budgetDate) { }
    /** version是该账户行版本，不能当作全局消息水位。 */
    public record AccountView(String limitAmount, String heldAmount, String chargedAmount, String availableAmount, String version) {
        static AccountView from(BudgetAccount a) {
            return new AccountView(a.limitAmount().toPlainString(), a.heldAmount().toPlainString(), a.chargedAmount().toPlainString(),
                    a.availableAmount().toPlainString(), Long.toString(a.version()));
        }
    }
    /** 账本、传输和查询投影独立解释；未提供时间保持null。 */
    public record FamilyView(ChargeMode family, String heldCount, String heldAmount, String expiredHeldCount,
            String ledgerCount, String ledgerAmount, String missingLedgerCount, String invalidLedgerCount,
            String pendingOutboxCount, String deadOutboxCount, String missingOutboxCount, String oldestPendingAt,
            String factCount, String factAmount, String missingFactCount, String invalidFactCount, String unexpectedFactCount, String lastAppliedAt,
            boolean summaryPresent, String summaryCount, String summaryAmount, ProjectionStatus projectionStatus) {
        static FamilyView from(FamilySummary f) {
            return new FamilyView(f.family(), Long.toString(f.heldCount()), f.heldAmount(), Long.toString(f.expiredHeldCount()),
                    Long.toString(f.ledgerCount()), f.ledgerAmount(), Long.toString(f.missingLedgerCount()), Long.toString(f.invalidLedgerCount()),
                    Long.toString(f.pendingOutboxCount()), Long.toString(f.deadOutboxCount()), Long.toString(f.missingOutboxCount()), time(f.oldestPendingAt()),
                    Long.toString(f.factCount()), f.factAmount(), Long.toString(f.missingFactCount()), Long.toString(f.invalidFactCount()), Long.toString(f.unexpectedFactCount()),
                    time(f.lastAppliedAt()), f.summaryPresent(), Long.toString(f.summaryCount()), f.summaryAmount(), f.projectionStatus());
        }
    }
    private static String time(Instant value) { return value == null ? null : value.toString(); }
}
