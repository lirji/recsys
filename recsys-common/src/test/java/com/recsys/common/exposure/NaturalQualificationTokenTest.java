package com.recsys.common.exposure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 值/签名契约测试只使用测试政策，不代表自然曝光的业务数值已经获批。 */
@Tag("natural-exposure")
class NaturalQualificationTokenTest {
    static final String OLD = Base64.getEncoder().encodeToString(new byte[32]);
    static final String NEW = Base64.getEncoder().encodeToString("distinct-test-rotation-key-with-64-bytes-xxxxxxxxxxxxxxxxxxxxxxxxxx".getBytes(StandardCharsets.US_ASCII));
    static final NaturalQualification.Policy POLICY = new NaturalQualification.Policy("test-only", 6000, 1200, 120000);

    NaturalQualification qualification(NaturalQualification.Policy policy) {
        Instant now = Instant.parse("2026-10-15T00:00:00.123Z");
        return new NaturalQualification(UUID.fromString("fa6aeed9-8d96-46a9-a755-fba0af14be0c"),
                UUID.fromString("b7cb55ed-2a0a-4553-a6a6-4cbbd26f46b9"), "主体-😀", Long.MAX_VALUE, Long.MAX_VALUE - 1,
                50, NaturalQualification.Kind.SEARCH, NaturalQualification.Traffic.SANDBOX,
                "搜索", "rank:v1;召回:热门", "a".repeat(64), policy, now, now.plusMillis(policy.ttlMs()));
    }

    @Test void frozenV1WireGoldenRemainsReadableAndByteIdentical() throws Exception {
        String golden;
        try(var in=getClass().getResourceAsStream("/exposure/natural-qualification-v1.txt")) {
            assertNotNull(in); golden=new String(in.readAllBytes(),StandardCharsets.US_ASCII).strip();
        }
        var codec=new NaturalQualificationToken("old",Map.of("old",OLD));
        assertEquals(qualification(POLICY),codec.verify(golden));
        assertEquals(golden,codec.sign(qualification(POLICY)),"同版本编码变化必须显式升级协议，不能静默破坏在途资格");
    }

    @Test void preservesLargeIdsUnicodeMillisAndExplicitPolicy() {
        var codec = new NaturalQualificationToken("old", Map.of("old", OLD));
        var q = qualification(POLICY); String token = codec.sign(q);
        assertEquals(q, codec.verify(token));
        assertEquals(token, codec.sign(q), "同一绑定和 key 产生同一票据");
        assertFalse(q.toString().contains(q.subject()));
        assertFalse(token.contains(q.subject()));
        assertEquals(POLICY, codec.verify(token).policy());
    }

    @Test void feedKindPreservesFinalSlotWithoutChangingExistingGolden() {
        var q=qualification(POLICY); var codec=new NaturalQualificationToken("old",Map.of("old",OLD));
        var feed=new NaturalQualification(q.operationId(),q.qualificationId(),q.subject(),q.userId(),q.itemId(),49,
                NaturalQualification.Kind.FEED,q.trafficClass(),"feed",q.bucket(),q.contentHash(),q.policy(),q.issuedAt(),q.expiresAt());
        assertEquals(feed,codec.verify(codec.sign(feed))); assertNotEquals(codec.sign(q),codec.sign(feed));
    }

    @Test void oldKeyCanVerifyDuringRotationButVerifierCannotIssue() {
        var old = new NaturalQualificationToken("old", Map.of("old", OLD));
        var rotating = new NaturalQualificationToken("new", Map.of("old", OLD, "new", NEW));
        var verifier = new NaturalQualificationToken(null, Map.of("old", OLD, "new", NEW));
        var q = qualification(POLICY);
        assertEquals(q, rotating.verify(old.sign(q))); assertEquals(q, verifier.verify(rotating.sign(q)));
        assertThrows(IllegalStateException.class, () -> verifier.sign(q));
        assertThrows(IllegalArgumentException.class, () -> new NaturalQualificationToken(null, Map.of("new", NEW)).verify(old.sign(q)));
    }

    @Test void tamperedVersionKeyPayloadSignatureAndAdvertisingPurposeAreRejected() {
        var codec = new NaturalQualificationToken("old", Map.of("old", OLD));
        String token = codec.sign(qualification(POLICY));
        String[] parts = token.split("\\.");
        for (String altered : List.of(token.replace("nq1.", "nq2."), token.replace(".old.", ".new."),
                token.replace(parts[2], parts[2].substring(0, parts[2].length()-1)),
                token.substring(0, token.length()-1), "v1.qualification.old." + parts[2] + "." + parts[3])) {
            assertEquals("自然资格证明非法", assertThrows(IllegalArgumentException.class, () -> codec.verify(altered)).getMessage());
        }
    }

    @Test void canonicalBase64AndExactPayloadLengthAreRequiredEvenWithValidMac() throws Exception {
        var codec = new NaturalQualificationToken("old", Map.of("old", OLD));
        String token = codec.sign(qualification(POLICY)); String[] parts = token.split("\\.");
        byte[] original = Base64.getUrlDecoder().decode(parts[2]);
        byte[] extra = Arrays.copyOf(original, original.length + 1);
        String content = "nq1.old." + Base64.getUrlEncoder().withoutPadding().encodeToString(extra);
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(Base64.getDecoder().decode(OLD), "HmacSHA256"));
        String appended = content + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> codec.verify(appended));
        assertThrows(IllegalArgumentException.class, () -> codec.verify(token + "="));
        assertThrows(IllegalArgumentException.class, () -> codec.verify("x".repeat(NaturalQualification.MAX_TOKEN_LENGTH + 1)));
    }

    @Test void expiryIsNotCheckedByCodecAndPolicyHasNoHiddenDefault() {
        var codec = new NaturalQualificationToken("old", Map.of("old", OLD));
        var alternate = new NaturalQualification.Policy("test-alternate", 1, 1, 1);
        var q = qualification(alternate);
        assertEquals(q, codec.verify(codec.sign(q)), "受理时间必须由 behavior 数据库事务判定");
        assertNotEquals(codec.sign(qualification(POLICY)), codec.sign(q));
        assertThrows(IllegalArgumentException.class, () -> new NaturalQualification.Policy("bad", 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new NaturalQualification.Policy("bad", 10001, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new NaturalQualification.Policy("bad", 5000, 1000, 999));
        assertThrows(IllegalArgumentException.class, () -> new NaturalQualification.Policy("bad", 5000, 1000, 86400001));
    }

    @Test void mismatchedExpiryOrLossyNanosCannotBeSigned() {
        var q = qualification(POLICY);
        assertThrows(IllegalArgumentException.class, () -> new NaturalQualification(q.operationId(), q.qualificationId(),
                q.subject(), q.userId(), q.itemId(), q.slot(), q.kind(), q.trafficClass(), q.scene(), q.bucket(), q.contentHash(),
                q.policy(), q.issuedAt(), q.expiresAt().plusMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> new NaturalQualification(q.operationId(), q.qualificationId(),
                q.subject(), q.userId(), q.itemId(), q.slot(), q.kind(), q.trafficClass(), q.scene(), q.bucket(), q.contentHash(),
                q.policy(), q.issuedAt().plusNanos(1), q.expiresAt().plusNanos(1)));
    }

    @Test void wireTimeBoundsAndContextHashAreIndependentOfSigningKey() {
        var q=qualification(POLICY);
        var one=new NaturalQualificationToken("old",Map.of("old",OLD));
        var two=new NaturalQualificationToken("new",Map.of("new",NEW));
        assertNotEquals(one.sign(q),two.sign(q));
        assertEquals(NaturalQualificationToken.contextHash(one.verify(one.sign(q))),NaturalQualificationToken.contextHash(two.verify(two.sign(q))));
        assertThrows(IllegalArgumentException.class,()->new NaturalQualification(q.operationId(),q.qualificationId(),q.subject(),q.userId(),q.itemId(),q.slot(),q.kind(),q.trafficClass(),
                q.scene(),q.bucket(),q.contentHash(),q.policy(),Instant.MIN,Instant.MIN.plusMillis(POLICY.ttlMs())));
    }

    @Test void productionAssemblyRejectsMissingOrUnapprovedPolicy() {
        assertEquals(new NaturalQualification.Policy("natural-text-v1",5000,1000,300000),NaturalQualification.approvedPolicy(NaturalQualification.TEXT_POLICY_V1));
        assertThrows(IllegalArgumentException.class,()->NaturalQualification.approvedPolicy(null));
        assertThrows(IllegalArgumentException.class,()->NaturalQualification.approvedPolicy(POLICY));
        assertThrows(IllegalArgumentException.class,()->NaturalQualification.approvedPolicy(new NaturalQualification.Policy("natural-text-v1",4999,1000,300000)));
    }

    @Test void keyConfigurationIsBoundedAndHasNoDefaultSecret() {
        assertThrows(IllegalArgumentException.class, () -> new NaturalQualificationToken("old", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new NaturalQualificationToken("missing", Map.of("old", OLD)));
        assertThrows(IllegalArgumentException.class, () -> new NaturalQualificationToken("old", Map.of("old", "eA==")));
        assertThrows(IllegalArgumentException.class, () -> new NaturalQualificationToken("old", Map.of("old.key", OLD)));
        var keys = new HashMap<String, String>(); for(int i=0;i<17;i++) keys.put("key"+i, OLD);
        assertThrows(IllegalArgumentException.class, () -> new NaturalQualificationToken(null, keys));
    }
}
