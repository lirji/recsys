package com.recsys.ad.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.delivery.SandboxDelivery;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** 用途隔离的 HMAC 证明；使用独立密钥环，既不接受算法降级，也不记录载荷。 */
public final class DeliveryTokenCodec {
    private final ObjectMapper json;
    private final String currentKeyId;
    private final Map<String, byte[]> keys;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public DeliveryTokenCodec(ObjectMapper json, String currentKeyId, Map<String, String> encodedKeys) {
        this.json = json.copy();
        this.currentKeyId = currentKeyId;
        var decoded = new HashMap<String, byte[]>();
        encodedKeys.forEach((id, key) -> {
            if (!id.matches("[A-Za-z0-9_-]{1,40}")) throw new IllegalArgumentException("签名 keyId 非法");
            byte[] bytes = Base64.getDecoder().decode(key);
            if (bytes.length < 32) throw new IllegalArgumentException("签名 key 至少 32 字节");
            decoded.put(id, bytes);
        });
        if (!decoded.containsKey(currentKeyId)) throw new IllegalArgumentException("当前签名 key 缺失");
        keys = Map.copyOf(decoded);
    }

    /** 完整前缀参与 MAC，防止更换版本、用途或 keyId。 */
    public String sign(String purpose, Object payload) {
        try {
            String content = "v1." + purpose + "." + currentKeyId + "." + ENCODER.encodeToString(json.writeValueAsBytes(payload));
            String token = content + "." + ENCODER.encodeToString(mac(content, keys.get(currentKeyId)));
            SandboxDelivery.requireToken(token);
            return token;
        } catch (java.io.IOException | java.security.GeneralSecurityException | IllegalArgumentException ex) { throw new IllegalStateException("无法签发交付证明"); }
    }

    /** 先验签再反序列化；期限由数据库时钟和对应生命周期处理，重试不刷新期限。 */
    public <T> T verify(String token, String purpose, Class<T> type) {
        try {
            SandboxDelivery.requireToken(token);
            String[] parts = token.split("\\.", -1);
            if (parts.length != 5 || !"v1".equals(parts[0]) || !purpose.equals(parts[1]) || !keys.containsKey(parts[2])) {
                throw new SandboxDelivery.InvalidProof();
            }
            String content = String.join(".", parts[0], parts[1], parts[2], parts[3]);
            byte[] signature = DECODER.decode(parts[4]);
            if (!MessageDigest.isEqual(signature, mac(content, keys.get(parts[2])))) throw new SandboxDelivery.InvalidProof();
            return json.readValue(DECODER.decode(parts[3]), type);
        } catch (java.io.IOException | java.security.GeneralSecurityException | IllegalArgumentException ex) { throw new SandboxDelivery.InvalidProof(); }
    }

    private static byte[] mac(String content, byte[] key) throws java.security.GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
    }
}
