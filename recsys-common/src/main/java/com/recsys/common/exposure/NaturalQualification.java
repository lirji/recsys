package com.recsys.common.exposure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 自然内容最终资格；明确测试来源，不能把返回候选或旧曝光记录当作已接受事实。 */
public record NaturalQualification(
        UUID operationId, UUID qualificationId, String subject, long userId, long itemId,
        int slot, Kind kind, Traffic trafficClass, String scene, String bucket,
        String contentHash, Policy policy, Instant issuedAt, Instant expiresAt) {

    public static final int MAX_SLOTS = 50;
    public static final int MAX_TOKEN_LENGTH = 16384;
    /** 已获业务确认的首版自然图文政策；配置仍须显式给定，不因此自动开启能力。 */
    public static final Policy TEXT_POLICY_V1 = new Policy("natural-text-v1", 5000, 1000, 300000);

    /** 正式装配只接受已批准元组；纯机制测试可显式注入 test-only 政策。 */
    public static Policy approvedPolicy(Policy policy) {
        if (!TEXT_POLICY_V1.equals(policy)) throw new IllegalArgumentException("自然曝光政策未获首版批准");
        return policy;
    }

    /** 稳定协议码，不使用 ordinal；尚不承载 S10 的最终 feed 位置。 */
    public enum Kind { RECOMMEND, SEARCH }
    /** 首版只允许 sandbox，不能由客户端升级为正式样本来源。 */
    public enum Traffic { SANDBOX }

    /** 强制完整绑定与毫秒精度，避免编码前后悄然截断或丢失业务身份。 */
    public NaturalQualification {
        Objects.requireNonNull(operationId); Objects.requireNonNull(qualificationId);
        requireText(subject, 512); requireText(scene, 100);
        Objects.requireNonNull(kind); Objects.requireNonNull(trafficClass); Objects.requireNonNull(policy);
        if (userId <= 0 || itemId <= 0 || slot < 1 || slot > MAX_SLOTS) throw new IllegalArgumentException("自然资格身份/位置非法");
        if (bucket == null || bucket.length() > 2000) throw new IllegalArgumentException("实验标签非法");
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("内容摘要非法");
        Objects.requireNonNull(issuedAt); Objects.requireNonNull(expiresAt);
        try { issuedAt.toEpochMilli(); expiresAt.toEpochMilli(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("资格时间超出 wire 毫秒边界"); }
        if (issuedAt.getNano() % 1_000_000 != 0 || expiresAt.getNano() % 1_000_000 != 0
                || !issuedAt.plusMillis(policy.ttlMs()).equals(expiresAt)) {
            throw new IllegalArgumentException("资格时间必须为毫秒且期限与政策一致");
        }
    }

    /** 政策由部署批准后显式提供，这里只限制资源/编码边界，不设置自然曝光业务默认值。 */
    public record Policy(String version, int minRatioBps, int minDurationMs, long ttlMs) {
        public Policy {
            if (version == null || !version.matches("[A-Za-z0-9_.-]{1,64}")
                    || minRatioBps < 1 || minRatioBps > 10000 || minDurationMs < 1 || minDurationMs > 300000
                    || ttlMs < minDurationMs || ttlMs > 86400000L) throw new IllegalArgumentException("自然可见政策非法");
        }
    }

    /** 调试输出仅保留关联 ID，不将主体和可复用凭据写日志。 */
    @Override public String toString() {
        return "NaturalQualification[qualificationId=" + qualificationId + ",operationId=" + operationId + "]";
    }

    private static void requireText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("自然资格文本非法");
    }
}
