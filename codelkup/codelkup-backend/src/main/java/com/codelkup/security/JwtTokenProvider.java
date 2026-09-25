package com.codelkup.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与校验。
 *
 * <p>采用双 token 方案：
 * <ul>
 *   <li><b>accessToken</b>（短期，默认 30 分钟）—— 携带在请求头，用于接口鉴权。
 *       无状态，不查库，性能好。</li>
 *   <li><b>refreshToken</b>（长期，默认 7 天）—— 仅用于换取新的 accessToken。
 *       存 Redis 白名单，可主动失效（登出时删除）。</li>
 * </ul>
 *
 * <p><b>为什么 accessToken 要有 jti（唯一 ID）？</b>
 * 登出时把 jti 写入 Redis 黑名单，在 token 自然过期前拦住它。
 * 否则登出后旧 token 在有效期内仍可用，是明显的安全漏洞。
 *
 * @author 刘
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    /** HS256 要求密钥至少 256 位（32 字节） */
    private static final int MIN_SECRET_BYTES = 32;

    @Value("${codelkup.jwt.secret}")
    private String secret;

    @Value("${codelkup.jwt.issuer}")
    private String issuer;

    @Value("${codelkup.jwt.access-token-ttl-seconds}")
    private long accessTtlSeconds;

    @Value("${codelkup.jwt.refresh-token-ttl-seconds}")
    private long refreshTtlSeconds;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // 启动期就拦住弱密钥，避免"跑起来了但签名不安全"的隐性风险
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "codelkup.jwt.secret 长度不足：" + keyBytes.length + " 字节，HS256 要求至少 " + MIN_SECRET_BYTES + " 字节");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT 初始化完成 | issuer={} | accessTtl={}s | refreshTtl={}s",
                issuer, accessTtlSeconds, refreshTtlSeconds);
    }

    // ==================== 签发 ====================

    public String createAccessToken(Long userId) {
        return createToken(userId, TYPE_ACCESS, accessTtlSeconds);
    }

    public String createRefreshToken(Long userId) {
        return createToken(userId, TYPE_REFRESH, refreshTtlSeconds);
    }

    private String createToken(Long userId, String type, long ttlSeconds) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ttlSeconds * 1000L);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())        // jti —— 登出黑名单靠它
                .subject(String.valueOf(userId))
                .issuer(issuer)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_TYPE, type)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    // ==================== 解析 ====================

    /**
     * 解析 token 并返回 claims。
     *
     * @return 解析成功返回 claims；无效/过期返回 {@code null}（不抛异常，调用方按"未认证"处理）
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.debug("token 已过期 | {}", e.getMessage());
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("token 无效 | {}", e.getMessage());
            return null;
        }
    }

    public Long getUserId(Claims claims) {
        Object uid = claims.get(CLAIM_USER_ID);
        if (uid instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    public boolean isAccessToken(Claims claims) {
        return TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    // ==================== TTL 查询（供缓存 key 设置过期时间）====================

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }

    public long getRefreshTtlSeconds() {
        return refreshTtlSeconds;
    }

    /**
     * 计算 token 剩余有效秒数。
     *
     * <p>用于设置黑名单 key 的 TTL —— 只需要拦到 token 自然过期为止，
     * 之后它会自己失效，无需永久占用 Redis 空间。
     */
    public long getRemainingSeconds(Claims claims) {
        long remainMillis = claims.getExpiration().getTime() - System.currentTimeMillis();
        return Math.max(remainMillis / 1000, 0);
    }
}
