package com.recsys.platform.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InternalTokenTest {

    private static final String SECRET = "unit-test-internal-secret-0123456789";

    @Test
    void mintThenVerifyRoundTrips() {
        long now = 1_000_000L;
        String token = InternalToken.mint("u42", "ADMIN,ADVERTISER", "edge", 300, now, SECRET);
        InternalToken.Claims c = InternalToken.verify(token, now + 10, SECRET);
        assertEquals("u42", c.subject());
        assertEquals("ADMIN,ADVERTISER", c.roles());
        assertEquals("edge", c.scene());
        assertEquals(now + 300, c.exp());
    }

    @Test
    void rejectsExpiredToken() {
        long now = 1_000_000L;
        String token = InternalToken.mint("u1", "USER", "edge", 60, now, SECRET);
        assertNull(InternalToken.verify(token, now + 61, SECRET), "过期令牌应校验失败");
    }

    @Test
    void rejectsWrongSecret() {
        long now = 1_000_000L;
        String token = InternalToken.mint("u1", "USER", "edge", 300, now, SECRET);
        assertNull(InternalToken.verify(token, now, "another-secret-abcdefghijklmnop"), "换密钥应校验失败");
    }

    @Test
    void rejectsTamperedPayload() {
        long now = 1_000_000L;
        String token = InternalToken.mint("u1", "USER", "edge", 300, now, SECRET);
        // 篡改 payload 段(改身份/角色)后签名不匹配
        String tampered = "dGFtcGVy." + token.substring(token.indexOf('.') + 1);
        assertNull(InternalToken.verify(tampered, now, SECRET), "篡改后应校验失败");
    }

    @Test
    void rejectsMalformedToken() {
        long now = 1_000_000L;
        assertNull(InternalToken.verify(null, now, SECRET));
        assertNull(InternalToken.verify("", now, SECRET));
        assertNull(InternalToken.verify("no-dot", now, SECRET));
    }
}
