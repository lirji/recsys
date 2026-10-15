package com.recsys.common.ad.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 预占首次决策与可演进当前状态分离；重复 prepare 不能把已释放义务重新占用。 */
public record Reservation(Prepared prepared, State state, long version, Instant closedAt, String closeReason) {
    /** 当前合法状态；捕获和释放只允许从 HELD 到一个终态。 */
    public enum State { HELD, REJECTED, RELEASED, EXPIRED, CAPTURED }
    /** 首次命令结论永久保留，即使后来补预算也不能改变原拒绝。 */
    public enum Decision { HELD, INSUFFICIENT_BUDGET, QUOTE_EXPIRED, ACCOUNT_MISSING }

    /** 不可变 prepare 结果；预算日、金额和期限均来自服务端数据库。 */
    public record Prepared(UUID reservationId, UUID quoteId, long advertiserId, long adId, String subjectKey,
                           LocalDate budgetDate, BigDecimal amount, Instant expiresAt, Decision decision, Instant createdAt) {
        public Prepared {
            validateSubject(subjectKey);
            if (reservationId == null || quoteId == null || advertiserId <= 0 || adId <= 0 || budgetDate == null
                    || expiresAt == null || decision == null || createdAt == null) {
                throw new IllegalArgumentException("预占快照不完整");
            }
            amount = CnyAmount.exact(amount);
        }
    }

    /** 主体必须来自可信交付上下文；禁止空主体造成无作用域查询。 */
    public static void validateSubject(String subjectKey) {
        if (subjectKey == null || subjectKey.isBlank() || subjectKey.length() > 200) {
            throw new IllegalArgumentException("交付主体必须非空且不超过 200 字符");
        }
    }

    /** 身份校验独立于查询命中，避免取得 UUID 即可以释放其他用户的占用。 */
    public void requireSubject(String subjectKey) {
        validateSubject(subjectKey);
        if (!prepared.subjectKey().equals(subjectKey)) throw new AccessDenied();
    }

    /** 业务幂等键冲突，不覆盖既有预占。 */
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("预占标识或报价已绑定其他命令"); }
    }

    /** 对外适配层须映射为无权限，不能吞掉后继续释放。 */
    public static final class AccessDenied extends RuntimeException {
        public AccessDenied() { super("交付主体无权访问该预占"); }
    }
}
