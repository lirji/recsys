package com.recsys.ad.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.delivery.SandboxDelivery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 验证用途、密钥轮换、篡改和缺失配置都不能绕过证明校验。 */
@Tag("money-chain")
class DeliveryTokenCodecTest {
    private static String key(int value) { var bytes = new byte[32]; java.util.Arrays.fill(bytes, (byte)value); return Base64.getEncoder().encodeToString(bytes); }
    @Test
    void verifiesPurposeAndRejectsTamperingWithoutPayloadLeak() {
        var codec = new DeliveryTokenCodec(new ObjectMapper(), "a", Map.of("a", key(1)));
        var token = codec.sign("offer", Map.of("secret", "payload"));
        assertEquals(Map.of("secret", "payload"), codec.verify(token, "offer", Map.class));
        assertThrows(SandboxDelivery.InvalidProof.class, () -> codec.verify(token, "qualification", Map.class));
        var parts = token.split("\\."); parts[3] = Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes());
        assertThrows(SandboxDelivery.InvalidProof.class, () -> codec.verify(String.join(".", parts), "offer", Map.class));
        assertThrows(SandboxDelivery.InvalidProof.class, () -> codec.verify("x".repeat(17000), "offer", Map.class));
    }
    @Test
    void rotatingCurrentKeyRetainsOldVerificationOnlyWithExplicitOldKey() {
        var old = new DeliveryTokenCodec(new ObjectMapper(), "a", Map.of("a", key(1)));
        var both = new DeliveryTokenCodec(new ObjectMapper(), "b", Map.of("a", key(1), "b", key(2)));
        assertEquals("value", both.verify(old.sign("offer", "value"), "offer", String.class));
        assertThrows(SandboxDelivery.InvalidProof.class, () -> old.verify(both.sign("offer", "value"), "offer", String.class));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryTokenCodec(new ObjectMapper(), "a", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryTokenCodec(new ObjectMapper(), "a", Map.of("a", "eA==")));
    }
}
