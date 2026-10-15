package com.recsys.common.ad.billing;

import com.recsys.common.ad.delivery.SandboxDelivery;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

/** 图文 CPM 回执契约；观测是客户端声明，资金来源只取服务端资格和报价。 */
public final class ImpressionReceipt {
    public static final int FULL_VISIBLE_BPS = 10000;
    public static final int MIN_VISIBLE_BPS = 5000;
    public static final long MIN_VISIBLE_MS = 1000;
    public static final long MAX_VISIBLE_MS = FrozenCpmQuote.TTL_SECONDS * 1000;
    private ImpressionReceipt() {}

    /** 原 receiptId 同负载恢复；主体只能由认证边界注入。 */
    public record Command(UUID receiptId, String subject, String qualificationToken,
                          long visibleDurationMs, int minVisibleRatioBps, boolean pageVisible) {
        public Command {
            if (receiptId == null) throw new IllegalArgumentException("receiptId 必填");
            Reservation.validateSubject(subject); SandboxDelivery.requireToken(qualificationToken);
            if (visibleDurationMs < 0 || visibleDurationMs > MAX_VISIBLE_MS || minVisibleRatioBps < 0 || minVisibleRatioBps > FULL_VISIBLE_BPS) {
                throw new IllegalArgumentException("回执时长或可见比例越界");
            }
        }
        /** 带长度编码避免换行等分隔符造成摘要歧义；敏感 token 不落 receipt 表。 */
        public String payloadHash() {
            try {
                var bytes = new ByteArrayOutputStream();
                var data = new DataOutputStream(bytes);
                data.writeUTF(subject); data.writeUTF(qualificationToken);
                data.writeLong(visibleDurationMs); data.writeInt(minVisibleRatioBps); data.writeBoolean(pageVisible);
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException("无法计算回执摘要", ex);
            }
        }
        /** 服务端再比较签发至接受时间，排除早于资格签发的虚构连续观测。 */
        public boolean meetsPolicy(Instant issuedAt, Instant now) {
            return pageVisible && minVisibleRatioBps >= MIN_VISIBLE_BPS && visibleDurationMs >= MIN_VISIBLE_MS
                    && !now.isBefore(issuedAt.plusMillis(visibleDurationMs));
        }
    }
    /** 原决策保存；ALREADY_ACCEPTED 引用同一账本，不产生第二次事实。 */
    public enum Status { ACCEPTED, ALREADY_ACCEPTED, POLICY_NOT_MET, EXPIRED, NOT_HELD }

    /** 拒绝结果 chargedAmount 为零；接受/重复使用原账本金额与预算日。 */
    public record Result(UUID receiptId, UUID qualificationId, Status status, UUID ledgerId, UUID canonicalReceiptId,
                         String chargedAmount, LocalDate budgetDate, Instant acceptedAt, Instant decidedAt) {
        public Result {
            chargedAmount = CnyAmount.parse(chargedAmount).toPlainString();
            if (receiptId == null || qualificationId == null || status == null || decidedAt == null
                    || (accepted(status) && (ledgerId == null || canonicalReceiptId == null || budgetDate == null || acceptedAt == null))) {
                throw new IllegalArgumentException("回执结果不完整");
            }
            if ((!accepted(status) && (ledgerId != null || canonicalReceiptId != null || budgetDate != null || acceptedAt != null
                    || CnyAmount.parse(chargedAmount).signum() != 0))
                    || (status == Status.ACCEPTED && !receiptId.equals(canonicalReceiptId))) {
                throw new IllegalArgumentException("回执接受与拒绝字段不一致");
            }
        }
        /** HTTP 与投影共享接受语义，不能把持久化拒绝当作成功曝光。 */
        public boolean accepted() { return accepted(status); }
        private static boolean accepted(Status status) { return status == Status.ACCEPTED || status == Status.ALREADY_ACCEPTED; }
    }

    /** 事件金额为字符串，逐 ledgerId 去重，不能用账户最大版本丢弃旧金额事实。 */
    public record CapturedEvent(String eventId, String eventType, int schemaVersion, String trafficClass, String currency,
                                String priceUnit, String pricingVersion, String operationId, String qualificationId,
                                String receiptId, String advertiserId, String adId, String creativeId, String userId,
                                int slot, String budgetDate, String amount, long accountVersion, Instant acceptedAt) {
        public static final int SCHEMA_VERSION = 1;
        public static final String TYPE = "AdImpressionCapturedV1";
        public static final String TOPIC = "ad-impression-captured-v1";
    }
    /** 同 receiptId 的主体或观测变化属于业务冲突，不能覆盖旧结果。 */
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("回执标识已绑定其他请求"); }
    }
}
