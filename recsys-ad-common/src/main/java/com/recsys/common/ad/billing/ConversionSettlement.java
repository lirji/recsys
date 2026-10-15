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

/** CPA 双条件结算契约；回执首次决定和当前资金状态分开，等待不能解释为实收。 */
public final class ConversionSettlement {
    public static final String TOKEN_PURPOSE = "conversion-qualification";
    public static final String POLICY = "conversion-text-v1";
    private ConversionSettlement() {}

    public enum Decision { HELD, INSUFFICIENT_BUDGET, ACCOUNT_MISSING, QUOTE_EXPIRED }
    public enum State { HELD, CAPTURED, RELEASED, EXPIRED, REJECTED }
    public enum Progress { WAITING_FOR_BOTH, WAITING_FOR_VISIBLE, WAITING_FOR_CONVERSION, CAPTURED, RELEASED, EXPIRED, REJECTED }
    public enum VisibleStatus { ACCEPTED_WAITING, ACCEPTED_CAPTURED, ALREADY_ACCEPTED, POLICY_NOT_MET, EXPIRED, NOT_HELD }
    public enum OutcomeStatus { ACCEPTED_WAITING, ACCEPTED_CAPTURED, DUPLICATE_QUALIFICATION, OUTSIDE_ATTRIBUTION, EXPIRED, NOT_HELD }
    public enum CloseReason { CONVERSION_CAPTURED, DELIVERY_ABORTED, EXPIRED }

    /** 摘要绑定整个不可变报价，避免把长标题或精确计价依据重复装进客户端token。 */
    public record Proof(UUID qualificationId, String subject, String quoteHash, LocalDate budgetDate, String policy) {
        public Proof {
            Reservation.validateSubject(subject);
            if (qualificationId == null || quoteHash == null || !quoteHash.matches("[0-9a-f]{64}")
                    || budgetDate == null || !POLICY.equals(policy)) throw new IllegalArgumentException("转化证明不完整");
        }
        @Override public String toString() { return "ConversionProof[qualificationId=" + qualificationId + "]"; }
    }

    /** 不可变准备结果；只有HELD签发token，已释放后恢复此结果也不会再次预占。 */
    public record Prepared(UUID qualificationId, Decision decision, LocalDate budgetDate, String amount,
                           Instant issuedAt, Instant expiresAt, Instant preparedAt, String qualificationToken) {
        public Prepared {
            amount = CnyAmount.parse(amount).toPlainString();
            if (qualificationId == null || decision == null || budgetDate == null || issuedAt == null || expiresAt == null
                    || preparedAt == null || !expiresAt.equals(issuedAt.plusSeconds(FrozenConversionQuote.TTL_SECONDS))
                    || (decision == Decision.HELD) != (qualificationToken != null)) throw new IllegalArgumentException("转化准备结果非法");
            if (qualificationToken != null) SandboxDelivery.requireToken(qualificationToken);
        }
        @Override public String toString() { return "ConversionPrepared[qualificationId=" + qualificationId + ",decision=" + decision + "]"; }
    }

    /** 只有CAPTURED才有chargedAmount，合法零元收费同样必须存在ledgerId。 */
    public record Snapshot(Prepared prepared, State state, long version, UUID visibleReceiptId, UUID outcomeId,
                           UUID ledgerId, String chargedAmount, Instant closedAt, CloseReason closeReason) {
        public Snapshot {
            Objects.requireNonNull(prepared); Objects.requireNonNull(state);
            boolean captured = state == State.CAPTURED;
            boolean terminal = state == State.CAPTURED || state == State.RELEASED || state == State.EXPIRED;
            if (version < 0 || captured != (ledgerId != null) || captured != (chargedAmount != null)
                    || (captured && (visibleReceiptId == null || outcomeId == null))
                    || (state == State.HELD && visibleReceiptId != null && outcomeId != null)
                    || terminal != (closedAt != null) || terminal != (closeReason != null)
                    || (captured && CnyAmount.parse(chargedAmount).compareTo(CnyAmount.parse(prepared.amount())) != 0)) {
                throw new IllegalArgumentException("转化结算状态不一致");
            }
            if (chargedAmount != null) chargedAmount = CnyAmount.parse(chargedAmount).toPlainString();
        }
        /** 等待的具体条件由持久化事实推导，不以原ACK猜测当前收费是否完成。 */
        public Progress progress() {
            return switch (state) {
                case HELD -> visibleReceiptId != null ? Progress.WAITING_FOR_CONVERSION
                        : outcomeId != null ? Progress.WAITING_FOR_VISIBLE : Progress.WAITING_FOR_BOTH;
                case CAPTURED -> Progress.CAPTURED;
                case RELEASED -> Progress.RELEASED;
                case EXPIRED -> Progress.EXPIRED;
                case REJECTED -> Progress.REJECTED;
            };
        }
    }

    /** 仅展示主体可提交此命令；客户端不能指定金额、广告主或接收时间。 */
    public record VisibleCommand(UUID receiptId,String subject,String qualificationToken,long visibleDurationMs,
                                 int minVisibleRatioBps,boolean pageVisible) {
        public VisibleCommand {
            Objects.requireNonNull(receiptId); Reservation.validateSubject(subject); SandboxDelivery.requireToken(qualificationToken);
            if(visibleDurationMs<0 || visibleDurationMs>ImpressionReceipt.MAX_VISIBLE_MS || minVisibleRatioBps<0
                    || minVisibleRatioBps>ImpressionReceipt.FULL_VISIBLE_BPS) throw new IllegalArgumentException("CPA可见观测非法");
        }
        /** 显然早于签发时间的观测不能满足曝光依赖。 */
        public boolean meetsPolicy(Instant issued,Instant now) {
            return pageVisible && visibleDurationMs>=ImpressionReceipt.MIN_VISIBLE_MS && minVisibleRatioBps>=ImpressionReceipt.MIN_VISIBLE_BPS
                    && !now.isBefore(issued.plusMillis(visibleDurationMs));
        }
        /** 原主体、资格和完整观测共同决定幂等内容。 */
        public String payloadHash() { return hash(subject,qualificationToken,Long.toString(visibleDurationMs),Integer.toString(minVisibleRatioBps),Boolean.toString(pageVisible)); }
        @Override public String toString() { return "ConversionVisibleCommand[receiptId="+receiptId+"]"; }
    }
    /** advertiser和actor必须来自已授权边界；actor只审计，允许当前其他有权主体恢复同业务事件。 */
    public record ConversionCommand(long advertiserId,UUID eventId,String actor,String qualificationToken,Instant occurredAt) {
        public ConversionCommand {
            if(advertiserId<=0 || eventId==null || occurredAt==null || occurredAt.getNano()%1000!=0) throw new IllegalArgumentException("CPA业务事件非法或超过微秒精度");
            Reservation.validateSubject(actor); SandboxDelivery.requireToken(qualificationToken);
        }
        /** 业务内容不含调用人，归属重新授权由调用边界负责；相同业务ID不能改归因或发生时间。 */
        public String payloadHash() { return hash(Long.toString(advertiserId),eventId.toString(),qualificationToken,occurredAt.toString()); }
        @Override public String toString() { return "ConversionCommand[eventId="+eventId+"]"; }
    }
    /** 原可见决定不可变，查询另带当前资金快照。 */
    public record VisibleReceipt(UUID receiptId,UUID qualificationId,VisibleStatus status,UUID canonicalReceiptId,Instant acceptedAt,Instant decidedAt) {
        public VisibleReceipt {
            Objects.requireNonNull(receiptId); Objects.requireNonNull(qualificationId); Objects.requireNonNull(status); Objects.requireNonNull(decidedAt);
            boolean accepted=status==VisibleStatus.ACCEPTED_WAITING || status==VisibleStatus.ACCEPTED_CAPTURED || status==VisibleStatus.ALREADY_ACCEPTED;
            if(accepted!=(canonicalReceiptId!=null) || accepted!=(acceptedAt!=null)
                    || (accepted && status!=VisibleStatus.ALREADY_ACCEPTED && !receiptId.equals(canonicalReceiptId))) throw new IllegalArgumentException("CPA可见决定不一致");
        }
    }
    /** 每个授权业务事件都保留原事实；只有canonical事件可能随第二条件成为收费事实。 */
    public record Outcome(UUID outcomeId,long advertiserId,UUID eventId,UUID qualificationId,Instant occurredAt,Instant receivedAt,
                          OutcomeStatus status,UUID canonicalOutcomeId,Instant decidedAt) {
        public Outcome {
            Objects.requireNonNull(outcomeId); Objects.requireNonNull(eventId); Objects.requireNonNull(qualificationId);
            Objects.requireNonNull(occurredAt); Objects.requireNonNull(receivedAt); Objects.requireNonNull(status); Objects.requireNonNull(decidedAt);
            boolean canonical=status==OutcomeStatus.ACCEPTED_WAITING || status==OutcomeStatus.ACCEPTED_CAPTURED;
            if(advertiserId<=0 || (canonical && !outcomeId.equals(canonicalOutcomeId))
                    || (!canonical && status!=OutcomeStatus.DUPLICATE_QUALIFICATION && canonicalOutcomeId!=null)
                    || (status==OutcomeStatus.DUPLICATE_QUALIFICATION && (canonicalOutcomeId==null || outcomeId.equals(canonicalOutcomeId)))) {
                throw new IllegalArgumentException("CPA业务事实原决定不一致");
            }
        }
    }
    public record VisibleResult(VisibleReceipt receipt,Snapshot settlement) {}
    public record OutcomeResult(Outcome outcome,Snapshot settlement) {}

    /** 捕获事件引用业务eventId及内部事实ID；不暴露资格token或认证actor。 */
    public record CapturedEvent(String eventId,String eventType,int schemaVersion,String trafficClass,String currency,
            String bidType,String priceUnit,String pricingVersion,String operationId,String qualificationId,String visibleReceiptId,
            String outcomeId,String conversionEventId,String advertiserId,String adId,String creativeId,String itemId,String userId,
            int slot,String budgetDate,String amount,long accountVersion,Instant occurredAt,Instant acceptedAt) {
        public static final String TYPE="AdConversionCapturedV1";
        public static final String TOPIC="ad-conversion-captured-v1";
        public static final int SCHEMA_VERSION=1;
    }
    private static String hash(String... values) {
        try(var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes)) {
            for(String value:values) data.writeUTF(value);
            data.flush(); return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(java.io.IOException | java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("CPA命令摘要不可用",ex); }
    }
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("CPA业务身份已绑定其他请求"); }
    }
}
