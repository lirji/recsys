package com.recsys.common.ad.billing;

import com.recsys.common.ad.delivery.SandboxDelivery;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** CPC/OCPC 双条件结算契约；回执首次决定和当前资金状态分开，等待不能解释为实收。 */
public final class ClickSettlement {
    public static final String TOKEN_PURPOSE = "click-qualification";
    public static final String POLICY = "click-text-v1";
    private ClickSettlement() {}

    public enum Kind { VISIBLE, CLICK }
    public enum Decision { HELD, INSUFFICIENT_BUDGET, ACCOUNT_MISSING, QUOTE_EXPIRED }
    public enum State { HELD, CAPTURED, RELEASED, EXPIRED, REJECTED }
    public enum Progress { WAITING_FOR_BOTH, WAITING_FOR_VISIBLE, WAITING_FOR_CLICK, CAPTURED, RELEASED, EXPIRED, REJECTED }
    public enum ReceiptStatus { ACCEPTED_WAITING, ACCEPTED_CAPTURED, ALREADY_ACCEPTED, POLICY_NOT_MET, FRAUD_REJECTED, EXPIRED, NOT_HELD }
    public enum CloseReason { CLICK_CAPTURED, DELIVERY_ABORTED, FRAUD_REJECTED, EXPIRED }

    /** 摘要绑定整个不可变报价，避免把长标题或精确计价依据重复装进客户端token。 */
    public record Proof(UUID qualificationId, String subject, String quoteHash, LocalDate budgetDate, String policy) {
        public Proof {
            Reservation.validateSubject(subject);
            if (qualificationId == null || quoteHash == null || !quoteHash.matches("[0-9a-f]{64}")
                    || budgetDate == null || !POLICY.equals(policy)) throw new IllegalArgumentException("点击证明不完整");
        }
        @Override public String toString() { return "ClickProof[qualificationId=" + qualificationId + "]"; }
    }

    /** 不可变准备结果；只有HELD签发token，已释放后恢复此结果也不会再次预占。 */
    public record Prepared(UUID qualificationId, Decision decision, LocalDate budgetDate, String amount,
                           Instant issuedAt, Instant expiresAt, Instant preparedAt, String qualificationToken) {
        public Prepared {
            amount = CnyAmount.parse(amount).toPlainString();
            if (qualificationId == null || decision == null || budgetDate == null || issuedAt == null || expiresAt == null
                    || preparedAt == null || !expiresAt.equals(issuedAt.plusSeconds(FrozenClickQuote.TTL_SECONDS))
                    || (decision == Decision.HELD) != (qualificationToken != null)) throw new IllegalArgumentException("点击准备结果非法");
            if (qualificationToken != null) SandboxDelivery.requireToken(qualificationToken);
        }
        @Override public String toString() { return "ClickPrepared[qualificationId=" + qualificationId + ",decision=" + decision + "]"; }
    }

    /** 只有CAPTURED才有chargedAmount，合法零元收费同样必须存在ledgerId。 */
    public record Snapshot(Prepared prepared, State state, long version, UUID visibleReceiptId, UUID clickReceiptId,
                           UUID ledgerId, String chargedAmount, Instant closedAt, CloseReason closeReason) {
        public Snapshot {
            Objects.requireNonNull(prepared); Objects.requireNonNull(state);
            boolean captured = state == State.CAPTURED;
            boolean terminal = state == State.CAPTURED || state == State.RELEASED || state == State.EXPIRED;
            if (version < 0 || captured != (ledgerId != null) || captured != (chargedAmount != null)
                    || (captured && (visibleReceiptId == null || clickReceiptId == null))
                    || (state == State.HELD && visibleReceiptId != null && clickReceiptId != null)
                    || terminal != (closedAt != null) || terminal != (closeReason != null)
                    || (captured && CnyAmount.parse(chargedAmount).compareTo(CnyAmount.parse(prepared.amount())) != 0)) {
                throw new IllegalArgumentException("点击结算状态不一致");
            }
            if (chargedAmount != null) chargedAmount = CnyAmount.parse(chargedAmount).toPlainString();
        }
        /** 等待的具体条件由持久化事实推导，不以原ACK猜测当前收费是否完成。 */
        public Progress progress() {
            return switch (state) {
                case HELD -> visibleReceiptId != null ? Progress.WAITING_FOR_CLICK
                        : clickReceiptId != null ? Progress.WAITING_FOR_VISIBLE : Progress.WAITING_FOR_BOTH;
                case CAPTURED -> Progress.CAPTURED;
                case RELEASED -> Progress.RELEASED;
                case EXPIRED -> Progress.EXPIRED;
                case REJECTED -> Progress.REJECTED;
            };
        }
    }

    /** CLICK观测字段固定为空值语义；不允许调用方注入金额、时间或风控结果。 */
    public record Command(UUID receiptId, String subject, String qualificationToken, Kind kind,
                          long visibleDurationMs, int minVisibleRatioBps, boolean pageVisible) {
        public Command {
            Objects.requireNonNull(receiptId); Objects.requireNonNull(kind);
            Reservation.validateSubject(subject); SandboxDelivery.requireToken(qualificationToken);
            if (visibleDurationMs < 0 || visibleDurationMs > ImpressionReceipt.MAX_VISIBLE_MS
                    || minVisibleRatioBps < 0 || minVisibleRatioBps > ImpressionReceipt.FULL_VISIBLE_BPS
                    || (kind == Kind.CLICK && (visibleDurationMs != 0 || minVisibleRatioBps != 0 || pageVisible))) {
                throw new IllegalArgumentException("点击链观测字段非法");
            }
        }
        /** 服务端复核观测至少发生在本次资格签发之后，过早回执不能满足依赖。 */
        public boolean meetsPolicy(Instant issuedAt, Instant now) {
            return kind == Kind.CLICK || (pageVisible && visibleDurationMs >= ImpressionReceipt.MIN_VISIBLE_MS
                    && minVisibleRatioBps >= ImpressionReceipt.MIN_VISIBLE_BPS && !now.isBefore(issuedAt.plusMillis(visibleDurationMs)));
        }
        /** 带长度编码，原token只进入摘要；同ID变种类同样属于冲突。 */
        public String payloadHash() {
            try (var bytes = new ByteArrayOutputStream(); var data = new DataOutputStream(bytes)) {
                data.writeUTF(subject); data.writeUTF(qualificationToken); data.writeUTF(kind.name());
                data.writeLong(visibleDurationMs); data.writeInt(minVisibleRatioBps); data.writeBoolean(pageVisible); data.flush();
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("点击命令摘要不可用", ex); }
        }
        @Override public String toString() { return "ClickCommand[receiptId=" + receiptId + ",kind=" + kind + "]"; }
    }

    /** 原决定不会随着等待条件满足而改写；canonical指向同种类最初接受的回执。 */
    public record Receipt(UUID receiptId, UUID qualificationId, Kind kind, ReceiptStatus status,
                          UUID canonicalReceiptId, Instant acceptedAt, Instant decidedAt) {
        public Receipt {
            Objects.requireNonNull(receiptId); Objects.requireNonNull(qualificationId); Objects.requireNonNull(kind);
            Objects.requireNonNull(status); Objects.requireNonNull(decidedAt);
            boolean accepted = status == ReceiptStatus.ACCEPTED_WAITING || status == ReceiptStatus.ACCEPTED_CAPTURED || status == ReceiptStatus.ALREADY_ACCEPTED;
            if (accepted != (canonicalReceiptId != null) || accepted != (acceptedAt != null)
                    || (accepted && status != ReceiptStatus.ALREADY_ACCEPTED && !receiptId.equals(canonicalReceiptId))) {
                throw new IllegalArgumentException("点击回执首次决定不一致");
            }
        }
    }
    public record Result(Receipt receipt, Snapshot settlement) {}

    /** 独立版本化点击事件，不能被CPM消费者当作曝光收费事实。 */
    public record CapturedEvent(String eventId, String eventType, int schemaVersion, String trafficClass, String currency,
            String bidType, String priceUnit, String pricingVersion, String operationId, String qualificationId,
            String visibleReceiptId, String clickReceiptId, String advertiserId, String adId, String creativeId, String itemId,
            String userId, int slot, String budgetDate, String amount, long accountVersion, Instant acceptedAt) {
        public static final String TYPE = "AdClickCapturedV1";
        public static final String TOPIC = "ad-click-captured-v1";
        public static final int SCHEMA_VERSION = 1;
    }
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("点击回执标识已绑定其他命令"); }
    }
}
