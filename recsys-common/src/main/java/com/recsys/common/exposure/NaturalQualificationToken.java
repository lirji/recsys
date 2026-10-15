package com.recsys.common.exposure;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** C08-A 的不透明 Java v1 资格票据；无框架依赖，签名与接收事务分别负责真实性和时间有效性。 */
public final class NaturalQualificationToken {
    private static final String VERSION = "nq1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final String currentKeyId;
    private final Map<String, byte[]> keys;

    /** currentKeyId 为 null 时只能验证；密钥必须显式注入，不允许开发默认密钥。 */
    public NaturalQualificationToken(String currentKeyId, Map<String, String> encodedKeys) {
        if (encodedKeys == null || encodedKeys.isEmpty() || encodedKeys.size() > 16) throw new IllegalArgumentException("资格验证密钥数量非法");
        var decoded = new HashMap<String, byte[]>();
        encodedKeys.forEach((id, encoded) -> {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,40}") || encoded == null || encoded.length() > 172) {
                throw new IllegalArgumentException("资格密钥配置非法");
            }
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length < 32 || key.length > 128) throw new IllegalArgumentException("资格密钥长度非法");
            decoded.put(id, key);
        });
        if (currentKeyId != null && !decoded.containsKey(currentKeyId)) throw new IllegalArgumentException("当前签发密钥不存在");
        this.currentKeyId = currentKeyId;
        this.keys = Map.copyOf(decoded);
    }

    /** 完整版本和 keyId 也参与签名；只能在最终交付持久化后向客户端交出此票据。 */
    public String sign(NaturalQualification qualification) {
        if (currentKeyId == null) throw new IllegalStateException("当前实例仅配置验证能力");
        String content = VERSION + "." + currentKeyId + "." + ENCODER.encodeToString(encode(qualification));
        String token = content + "." + ENCODER.encodeToString(mac(content, keys.get(currentKeyId)));
        if (token.length() > NaturalQualification.MAX_TOKEN_LENGTH) throw new IllegalArgumentException("自然资格超出编码长度");
        return token;
    }

    /** 先验签，再按固定版本解码；不在此判断到期，以免破坏已持久化结果的幂等恢复。 */
    public NaturalQualification verify(String token) {
        try {
            if (token == null || token.length() > NaturalQualification.MAX_TOKEN_LENGTH) throw invalid();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 4 || !VERSION.equals(parts[0]) || !keys.containsKey(parts[1])) throw invalid();
            byte[] payload = canonicalBase64(parts[2]);
            byte[] signature = canonicalBase64(parts[3]);
            String content = String.join(".", parts[0], parts[1], parts[2]);
            if (!MessageDigest.isEqual(signature, mac(content, keys.get(parts[1])))) throw invalid();
            try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
                UUID operation = uuid(input), qualification = uuid(input);
                String subject = input.readUTF(); long user = input.readLong(), item = input.readLong(); int slot = input.readInt();
                var kind = NaturalQualification.Kind.valueOf(input.readUTF());
                var traffic = NaturalQualification.Traffic.valueOf(input.readUTF());
                String scene = input.readUTF(), bucket = input.readUTF(), hash = input.readUTF();
                var policy = new NaturalQualification.Policy(input.readUTF(), input.readInt(), input.readInt(), input.readLong());
                var result = new NaturalQualification(operation, qualification, subject, user, item, slot, kind, traffic,
                        scene, bucket, hash, policy, Instant.ofEpochMilli(input.readLong()), Instant.ofEpochMilli(input.readLong()));
                if (input.read() != -1) throw invalid();
                return result;
            }
        } catch (IOException | IllegalArgumentException | NullPointerException ex) { throw invalid(); }
    }

    /** 资格上下文用固定 wire 编码摘要，避免 JSON 命名/属性顺序或签名 key 轮换改变幂等语义。 */
    public static String contextHash(NaturalQualification qualification) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(qualification))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("资格摘要算法不可用", ex); }
    }

    private static byte[] encode(NaturalQualification q) {
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            uuid(output, q.operationId()); uuid(output, q.qualificationId()); output.writeUTF(q.subject());
            output.writeLong(q.userId()); output.writeLong(q.itemId()); output.writeInt(q.slot());
            output.writeUTF(q.kind().name()); output.writeUTF(q.trafficClass().name()); output.writeUTF(q.scene());
            output.writeUTF(q.bucket()); output.writeUTF(q.contentHash()); output.writeUTF(q.policy().version());
            output.writeInt(q.policy().minRatioBps()); output.writeInt(q.policy().minDurationMs()); output.writeLong(q.policy().ttlMs());
            output.writeLong(q.issuedAt().toEpochMilli()); output.writeLong(q.expiresAt().toEpochMilli());
            output.flush(); return bytes.toByteArray();
        } catch (IOException ex) { throw new IllegalStateException("自然资格编码失败", ex); }
    }
    private static void uuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits()); output.writeLong(id.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream input) throws IOException { return new UUID(input.readLong(), input.readLong()); }
    private static byte[] canonicalBase64(String value) {
        byte[] bytes = DECODER.decode(value);
        if (!ENCODER.encodeToString(bytes).equals(value)) throw invalid();
        return bytes;
    }
    private static byte[] mac(String content, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("资格签名算法不可用", ex); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("自然资格证明非法"); }
}
