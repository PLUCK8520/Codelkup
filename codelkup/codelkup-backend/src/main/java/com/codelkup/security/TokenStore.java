package com.codelkup.security;

import com.codelkup.common.constant.RedisKeys;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Token 黑名单与 Refresh Token 白名单。
 *
 * <p><b>两个名单的区别（面试常问）</b>：
 * <ul>
 *   <li><b>黑名单</b>（accessToken）：登出后把 jti 拉黑，在自然过期前拒绝它。
 *       TTL = token 剩余有效期，过期后自动清理，不会无限增长。</li>
 *   <li><b>白名单</b>（refreshToken）：只有登记过的 refreshToken 才可用于换新。
 *       登出时删除，立即失效。</li>
 * </ul>
 *
 * @author 刘
 */
@Service
@RequiredArgsConstructor
public class TokenStore {

    private final StringRedisTemplate stringRedisTemplate;

    // ==================== accessToken 黑名单 ====================

    /**
     * 将 accessToken 的 jti 拉黑。
     *
     * @param claims token 解析结果
     */
    public void blacklistAccessToken(Claims claims) {
        long ttl = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
        if (ttl <= 0) {
            return;     // 已过期，无需拉黑
        }
        stringRedisTemplate.opsForValue().set(
                RedisKeys.TOKEN_BLACKLIST + claims.getId(),
                "1",
                ttl, TimeUnit.SECONDS
        );
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeys.TOKEN_BLACKLIST + jti));
    }

    // ==================== refreshToken 白名单 ====================

    /**
     * 登记 refreshToken。
     *
     * <p>一个用户只保留最新一个 refreshToken —— 重新登录会使旧 refreshToken 失效，
     * 这是有意的：避免同一账号在多处长期保持活跃会话。
     */
    public void saveRefreshToken(Long userId, String refreshToken) {
        stringRedisTemplate.opsForValue().set(
                RedisKeys.REFRESH_TOKEN + userId,
                refreshToken,
                7, TimeUnit.DAYS
        );
    }

    /** 校验 refreshToken 是否为当前有效的那一个 */
    public boolean isRefreshTokenValid(Long userId, String refreshToken) {
        String stored = stringRedisTemplate.opsForValue().get(RedisKeys.REFRESH_TOKEN + userId);
        return refreshToken != null && refreshToken.equals(stored);
    }

    public void removeRefreshToken(Long userId) {
        stringRedisTemplate.delete(RedisKeys.REFRESH_TOKEN + userId);
    }
}
