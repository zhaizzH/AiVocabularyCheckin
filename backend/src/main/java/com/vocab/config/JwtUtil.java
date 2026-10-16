package com.vocab.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

/**
 * JWT 签发与解析。
 *
 * <p>契约约束（《接口详细设计》§1.2）：登录成功后签发含 {@code userId} 与
 * {@code role} 的 token，有效期 <b>7 天</b>；算法 HS256。
 *
 * <p>载荷字段名为 {@code userId} / {@code role}，与拦截器解析约定保持一对一。
 *
 * <p><b>安全</b>：密钥只从配置（环境变量）读取，绝不硬编码在代码中；
 * 日志中绝不输出 token 内容。
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    /** HS256 要求的最小密钥长度（字节）。 */
    private static final int MIN_KEY_BYTES = 32;

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_ROLE = "role";

    private final String secret;
    private final long expireMs;

    private SecretKey signingKey;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expire-ms:604800000}") long expireMs) {
        this.secret = secret;
        this.expireMs = expireMs;
    }

    @PostConstruct
    void init() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length >= MIN_KEY_BYTES) {
            this.signingKey = Keys.hmacShaKeyFor(keyBytes);
            return;
        }
        // 密钥过短时，jjwt 的 Keys.hmacShaKeyFor 会抛 WeakKeyException 直接导致启动失败。
        // 为避免本地开发/演示环境因密钥长度问题起不来，这里用 SHA-256 派生出 32 字节密钥，
        // 并明确告警——生产环境必须提供 >= 32 字节的随机密钥。
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256").digest(keyBytes);
            this.signingKey = Keys.hmacShaKeyFor(derived);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论上不可达；不吞异常，直接暴露为启动失败。
            throw new IllegalStateException("无法初始化 JWT 签名密钥", e);
        }
        log.warn("JWT_SECRET 长度不足 {} 字节，已用 SHA-256 派生密钥。生产环境必须配置足够长度的随机密钥。",
                MIN_KEY_BYTES);
    }

    /**
     * 签发 token。
     *
     * @param userId 用户 id
     * @param role   角色（USER / ADMIN）
     */
    public String generateToken(Long userId, String role) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireMs);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_ROLE, role)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    /**
     * 解析并校验 token。
     *
     * @return 载荷；token 无效/过期/签名不符时返回 {@code null}
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // 只记 DEBUG：非法 token 是常见噪声（过期、被篡改），不该刷 WARN/ERROR。
            // 绝不记录 token 本身。
            log.debug("JWT 解析失败: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** 从载荷取 userId。 */
    public Long getUserId(Claims claims) {
        Object value = claims.get(CLAIM_USER_ID);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 从载荷取 role。 */
    public String getRole(Claims claims) {
        Object value = claims.get(CLAIM_ROLE);
        return value == null ? null : String.valueOf(value);
    }
}
