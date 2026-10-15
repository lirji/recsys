package com.recsys.common.ad.billing;

import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** 对账只解释已有账，不创造新的资金写权威；金额差额与传输/投影状态分别表达。 */
public final class BillingReconciliation {
    private BillingReconciliation() {}
    public enum FundsStatus { BALANCED, MISMATCH, ACCOUNT_MISSING }
    public enum ProjectionStatus { CAUGHT_UP, LAGGING, MISMATCH }

    /** 固定sandbox/CNY的单账户原预算日，不能把跨日义务归到查询当天。 */
    public record Scope(long advertiserId,LocalDate budgetDate) {
        public Scope {
            if(advertiserId<=0 || budgetDate==null)throw new IllegalArgumentException("对账范围非法");
            BudgetCommand.parseDate(budgetDate.toString());
        }
    }

    /** 每族独立记录笔数；零元事实也必须存在，汇总缺行与无法查询不是同一状态。 */
    public record FamilySummary(ChargeMode family,long heldCount,String heldAmount,long expiredHeldCount,
            long ledgerCount,String ledgerAmount,long missingLedgerCount,long invalidLedgerCount,
            long pendingOutboxCount,long deadOutboxCount,long missingOutboxCount,Instant oldestPendingAt,
            long factCount,String factAmount,long missingFactCount,long invalidFactCount,long unexpectedFactCount,Instant lastAppliedAt,
            boolean summaryPresent,long summaryCount,String summaryAmount) {
        public FamilySummary {
            Objects.requireNonNull(family);
            heldAmount=nonnegative(heldAmount);ledgerAmount=nonnegative(ledgerAmount);factAmount=nonnegative(factAmount);summaryAmount=nonnegative(summaryAmount);
            for(long count:new long[]{heldCount,expiredHeldCount,ledgerCount,missingLedgerCount,invalidLedgerCount,pendingOutboxCount,
                    deadOutboxCount,missingOutboxCount,factCount,missingFactCount,invalidFactCount,unexpectedFactCount,summaryCount}) {
                if(count<0)throw new IllegalArgumentException("对账计数不能为负");
            }
            if(expiredHeldCount>heldCount || (!summaryPresent && (summaryCount!=0 || new BigDecimal(summaryAmount).signum()!=0))) {
                throw new IllegalArgumentException("对账汇总状态不一致");
            }
        }
        /** 已存在坏事实或汇总偏差不能标为普通延迟，更不能靠重复加钱修复。 */
        public ProjectionStatus projectionStatus() {
            if(invalidFactCount>0 || unexpectedFactCount>0 || factCount!=summaryCount
                    || new BigDecimal(factAmount).compareTo(new BigDecimal(summaryAmount))!=0) return ProjectionStatus.MISMATCH;
            return missingFactCount>0?ProjectionStatus.LAGGING:ProjectionStatus.CAUGHT_UP;
        }
    }

    /** observedAt只是本次同库观察时间，不能解释为全局消息或跨任务提交水位。 */
    public record Snapshot(Scope scope,Instant observedAt,BudgetAccount account,List<FamilySummary> families) {
        public Snapshot {
            Objects.requireNonNull(scope);Objects.requireNonNull(observedAt);families=List.copyOf(families);
            if(families.size()!=ChargeMode.values().length || !families.stream().map(FamilySummary::family).toList().equals(List.of(ChargeMode.values()))) {
                throw new IllegalArgumentException("对账须完整包含固定顺序的三类资金事实");
            }
            if(account!=null && (account.advertiserId()!=scope.advertiserId() || !account.budgetDate().equals(scope.budgetDate()))) {
                throw new IllegalArgumentException("对账账户范围不一致");
            }
        }
        /** 差额为账户持有额减当前有效HELD明细；已到期未释放仍是账上义务。 */
        public String heldDelta() {
            return account==null?null:decimal(account.heldAmount().subtract(families.stream().map(f->new BigDecimal(f.heldAmount())).reduce(BigDecimal.ZERO,BigDecimal::add)));
        }
        /** 差额为账户消费额减逐笔账本；投影延迟不会改变该差额。 */
        public String chargedDelta() {
            return account==null?null:decimal(account.chargedAmount().subtract(families.stream().map(f->new BigDecimal(f.ledgerAmount())).reduce(BigDecimal.ZERO,BigDecimal::add)));
        }
        /** 资金与原子事件缺口属于资金链路异常，投影状态由各族另行解释。 */
        public FundsStatus fundsStatus() {
            if(account==null)return FundsStatus.ACCOUNT_MISSING;
            if(new BigDecimal(heldDelta()).signum()!=0 || new BigDecimal(chargedDelta()).signum()!=0
                    || families.stream().anyMatch(f->f.missingLedgerCount()>0 || f.invalidLedgerCount()>0 || f.missingOutboxCount()>0)) return FundsStatus.MISMATCH;
            return FundsStatus.BALANCED;
        }
    }
    private static String nonnegative(String value) {
        if(value==null || !value.matches("(?:0|[1-9][0-9]{0,30})\\.[0-9]{6}"))throw new IllegalArgumentException("对账金额须为六位精确小数");
        return value;
    }
    /** 聚合差额允许负数或超过单笔上限，不能为复用Money校验而截断异常证据。 */
    public static String decimal(BigDecimal value) { return Objects.requireNonNull(value).setScale(6,RoundingMode.UNNECESSARY).toPlainString(); }
}
