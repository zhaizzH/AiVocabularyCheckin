package com.vocab.config;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link JwtUtil} 单测。
 *
 * <p>重点覆盖三类必须失败的场景：被篡改的 token、用其它密钥签发的 token、
 * 已过期的 token。它们对应契约中的 401（code 1006）。
 */
class JwtUtilTest {

    /** 32 字节以上的密钥，可被 jjwt 直接使用。 */
    private static final String SECRET = "unit-test-secret-key-at-least-32-bytes-long!!";

    private static final long ONE_HOUR_MS = 3600_000L;

    private JwtUtil newUtil() {
        JwtUtil util = new JwtUtil(SECRET, ONE_HOUR_MS);
        util.init();
        return util;
    }

    @Test
    @DisplayName("签发后可解析出 userId 与 role")
    void generateAndParse() {
        JwtUtil util = newUtil();
        String token = util.generateToken(42L, "USER");
        assertNotNull(token);

        Claims claims = util.parseToken(token);
        assertNotNull(claims);
        assertEquals(42L, util.getUserId(claims));
        assertEquals("USER", util.getRole(claims));
    }

    @Test
    @DisplayName("ADMIN 角色应正确往返")
    void adminRoleRoundTrip() {
        JwtUtil util = newUtil();
        Claims claims = util.parseToken(util.generateToken(1L, "ADMIN"));
        assertNotNull(claims);
        assertEquals("ADMIN", util.getRole(claims));
    }

    @Test
    @DisplayName("被篡改的 token 必须解析失败（返回 null，而非抛异常）")
    void tamperedTokenReturnsNull() {
        JwtUtil util = newUtil();
        String token = util.generateToken(42L, "USER");
        // 改动签名段的最后一个字符
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertNull(util.parseToken(tampered));
    }

    @Test
    @DisplayName("用不同密钥签发的 token 必须被拒绝")
    void tokenFromOtherSecretRejected() {
        JwtUtil issuer = new JwtUtil("another-secret-key-also-long-enough-32b!!", ONE_HOUR_MS);
        issuer.init();
        String foreignToken = issuer.generateToken(42L, "USER");

        assertNull(newUtil().parseToken(foreignToken), "异源密钥签发的 token 不得通过校验");
    }

    @Test
    @DisplayName("过期 token 必须解析失败")
    void expiredTokenReturnsNull() {
        // 负数有效期 -> 签发即过期
        JwtUtil util = new JwtUtil(SECRET, -1000L);
        util.init();
        String expired = util.generateToken(42L, "USER");
        assertNull(util.parseToken(expired));
    }

    @Test
    @DisplayName("畸形字符串必须解析失败而不是抛异常")
    void malformedTokenReturnsNull() {
        JwtUtil util = newUtil();
        assertNull(util.parseToken("not-a-jwt"));
        assertNull(util.parseToken(""));
        assertNull(util.parseToken("a.b.c"));
    }

    @Test
    @DisplayName("密钥不足 32 字节时应派生出可用密钥而不是启动失败")
    void shortSecretIsDerived() {
        JwtUtil util = new JwtUtil("short", ONE_HOUR_MS);
        util.init();
        String token = util.generateToken(7L, "USER");
        Claims claims = util.parseToken(token);
        assertNotNull(claims, "短密钥经 SHA-256 派生后应可正常签发/校验");
        assertEquals(7L, util.getUserId(claims));
    }
}
