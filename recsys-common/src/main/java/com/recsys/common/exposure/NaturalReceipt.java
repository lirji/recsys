package com.recsys.common.exposure;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** 自然可见回执契约；接受自然事实不产生资金，也不复用广告领域的状态。 */
public final class NaturalReceipt {
    private NaturalReceipt() {}
    public enum Status { ACCEPTED, ALREADY_ACCEPTED, POLICY_NOT_MET, EXPIRED }
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("自然曝光幂等身份与原内容冲突"); }
    }
    public static final class AccessDenied extends RuntimeException {
        public AccessDenied() { super("自然曝光主体不匹配"); }
    }

    /** 观测值严格有界；主体只能由认证上下文给定。 */
    public record Command(UUID receiptId, String subject, String qualificationToken,
                          int visibleDurationMs, int minVisibleRatioBps, boolean pageVisible) {
        public Command {
            Objects.requireNonNull(receiptId); validateSubject(subject);
            if (qualificationToken == null || qualificationToken.isBlank()
                    || qualificationToken.length() > NaturalQualification.MAX_TOKEN_LENGTH
                    || visibleDurationMs < 0 || visibleDurationMs > 300000
                    || minVisibleRatioBps < 0 || minVisibleRatioBps > 10000) {
                throw new IllegalArgumentException("自然曝光观测格式非法");
            }
        }
        /** 固定二进制编码避免字段分隔符歧义；token 摘要落库，原凭据不落回执表。 */
        public String payloadHash() {
            try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
                output.writeLong(receiptId.getMostSignificantBits()); output.writeLong(receiptId.getLeastSignificantBits());
                output.writeUTF(subject); output.writeUTF(qualificationToken); output.writeInt(visibleDurationMs);
                output.writeInt(minVisibleRatioBps); output.writeBoolean(pageVisible); output.flush();
                return sha256(bytes.toByteArray());
            } catch (java.io.IOException ex) { throw new IllegalStateException("回执摘要无法编码", ex); }
        }
        /** 数据库时钟不能比签发时间早；声明连续时长不得大于实际经过时间。 */
        public boolean meetsPolicy(NaturalQualification q, Instant now) {
            return pageVisible && minVisibleRatioBps >= q.policy().minRatioBps()
                    && visibleDurationMs >= q.policy().minDurationMs()
                    && !now.isBefore(q.issuedAt().plusMillis(visibleDurationMs));
        }
        @Override public String toString() { return "NaturalReceipt.Command[receiptId=" + receiptId + "]"; }
    }

    /** 拒绝的 event/canonical/acceptedAt 必须为空，未知结果不能伪装成任何一个终态。 */
    public record Result(UUID receiptId, UUID qualificationId, Status status, UUID eventId,
                         UUID canonicalReceiptId, Instant acceptedAt, Instant decidedAt) {
        public Result {
            Objects.requireNonNull(receiptId); Objects.requireNonNull(qualificationId);
            Objects.requireNonNull(status); Objects.requireNonNull(decidedAt);
            boolean accepted = status == Status.ACCEPTED || status == Status.ALREADY_ACCEPTED;
            if (accepted ? !qualificationId.equals(eventId) || canonicalReceiptId == null || acceptedAt == null
                    : eventId != null || canonicalReceiptId != null || acceptedAt != null) {
                throw new IllegalArgumentException("自然回执结果不完整");
            }
            if (status == Status.ACCEPTED && !receiptId.equals(canonicalReceiptId)) throw new IllegalArgumentException("首次回执身份不一致");
        }
    }

    /** v1 不可变自然事实；分桶和内容摘要来自签名，无认证主体或凭据。 */
    public record Event(String eventId, String eventType, int schemaVersion, String trafficClass,
                        String operationId, String qualificationId, String receiptId, String userId, String itemId,
                        int slot, NaturalQualification.Kind kind, String scene, String bucket, String contentHash,
                        NaturalQualification.Policy policy, Instant issuedAt, Instant expiresAt, Instant acceptedAt) {
        public static final String TYPE = "NaturalImpressionAcceptedV1";
        public static final String TOPIC = "natural-impression-accepted-v1";
        public static final int VERSION = 1;
        /** 事件只从验签后的上下文构造，不从终端 JSON 拷贝归因字段。 */
        public static Event accepted(NaturalQualification q, UUID receipt, Instant now) {
            return new Event(q.qualificationId().toString(), TYPE, VERSION, q.trafficClass().name(),
                    q.operationId().toString(), q.qualificationId().toString(), receipt.toString(),
                    Long.toString(q.userId()), Long.toString(q.itemId()), q.slot(), q.kind(), q.scene(), q.bucket(),
                    q.contentHash(), q.policy(), q.issuedAt(), q.expiresAt(), now);
        }
    }

    /** UUID.fromString 接受缩写，此处要求完整规范身份，避免多种路径表示同一命令。 */
    public static UUID parseId(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("自然曝光 ID 格式非法");
        }
        return UUID.fromString(value);
    }
    /** 自然曝光使用同一主体长度约束，不截断外部认证标识。 */
    public static void validateSubject(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 512) throw new IllegalArgumentException("自然曝光主体非法");
    }
    /** 不输出原输入；只用于已规定编码的幂等/上下文摘要。 */
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA256 不可用", ex); }
    }
}
