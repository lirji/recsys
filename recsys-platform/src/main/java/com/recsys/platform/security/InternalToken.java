package com.recsys.platform.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 东西向内部令牌(P0):紧凑的 HMAC-SHA256 自签令牌,用于服务间(网关→下游、rec-engine→gRPC)传播可信身份。
 *
 * <p>刻意用纯 JDK 实现(无外部 JWT 库依赖):格式为 {@code base64url(payload) "." base64url(hmac)},
 * payload = {@code subject\nroles\nscene\nexp}。网关校验终端 JWT 后 {@link #mint} 一枚短时令牌注入下游 header;
 * 下游 {@link #verify} 校验签名与过期。这是内部零信任传播,即使某服务被直连也无法伪造身份。
 */
public final class InternalToken {

    private InternalToken() {
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    public record Claims(String subject, String roles, String scene, long exp) {
    }

    public static String mint(String subject, String roles, String scene,
                              long ttlSeconds, long nowEpochSec, String secret) {
        long exp = nowEpochSec + ttlSeconds;
        String payload = nullToEmpty(subject) + "\n" + nullToEmpty(roles) + "\n" + nullToEmpty(scene) + "\n" + exp;
        String p = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sig = B64.encodeToString(hmac(p, secret));
        return p + "." + sig;
    }

    /** 校验签名与过期;任何异常/不合法/过期均返回 null(调用方视为匿名)。 */
    public static Claims verify(String token, long nowEpochSec, String secret) {
        if (token == null || token.isBlank()) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return null;
        }
        String p = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        byte[] provided;
        try {
            provided = B64D.decode(sig);
        } catch (IllegalArgumentException e) {
            return null;
        }
        byte[] expected = hmac(p, secret);
        if (!MessageDigest.isEqual(expected, provided)) {
            return null;
        }
        String payload;
        try {
            payload = new String(B64D.decode(p), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String[] parts = payload.split("\n", -1);
        if (parts.length != 4) {
            return null;
        }
        long exp;
        try {
            exp = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (exp < nowEpochSec) {
            return null;
        }
        return new Claims(parts[0], parts[1], parts[2], exp);
    }

    private static byte[] hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
