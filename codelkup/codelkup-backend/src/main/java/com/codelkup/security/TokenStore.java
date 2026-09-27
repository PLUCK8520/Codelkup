package com.codelkup.security;

import com.codelkup.common.constant.RedisKeys;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
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

    /** accessToken 有效期 —— 撤销水位的 TTL 以它为准（水位必须"活到最后一个旧 token 过期"） */
    @Value("${codelkup.jwt.access-token-ttl-seconds}")
    private long accessTtlSeconds;

    /** refreshToken 有效期 —— 白名单的 TTL 以它为准（审计 informational：原为硬编码 7 天，改配置不生效） */
    @Value("${codelkup.jwt.refresh-token-ttl-seconds}")
    private long refreshTtlSeconds;

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
     *
     * <p>TTL 现在读 {@code codelkup.jwt.refresh-token-ttl-seconds}（默认 7 天）——
     * 审计发现原实现硬编码 7 天，把该配置改成 1 小时也不会生效（"文档说可配、实际不可配"）。
     */
    public void saveRefreshToken(Long userId, String refreshToken) {
        stringRedisTemplate.opsForValue().set(
                RedisKeys.REFRESH_TOKEN + userId,
                refreshToken,
                refreshTtlSeconds, TimeUnit.SECONDS
        );
    }

    /** refresh 轮换的原子 CAS 脚本（F1）：旧值匹配才写新值并续 TTL，两条命令不可分割 */
    private static final DefaultRedisScript<Long> ROTATE_REFRESH_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]) return 1 else return 0 end",
            Long.class);

    /**
     * 原子轮换 refreshToken（单次语义）。
     *
     * <p><b>为什么必须原子（F1，第二轮审计）</b>：此前 refresh 是"读校验 → 签发 → 写回"，
     * 与改密的"删除白名单"并发时存在 TOCTOU —— 删除后写回会让被盗 refresh 的会话复活，
     * 使"改密=踢下线"失效。CAS 让两种交错都安全：删除在前 → CAS 失败；删除在后 → 删掉新值。
     * 同一枚 refresh 的并发二次使用同样只有一枚能成功（另一枚 40102）。
     *
     * @return 旧值匹配并成功写入返回 true；不匹配/已被删除返回 false
     */
    public boolean rotateRefreshToken(Long userId, String expectedOld, String newToken) {
        Long result = stringRedisTemplate.execute(
                ROTATE_REFRESH_SCRIPT,
                List.of(RedisKeys.REFRESH_TOKEN + userId),
                expectedOld, newToken, String.valueOf(refreshTtlSeconds)
        );
        return result != null && result == 1L;
    }

    public void removeRefreshToken(Long userId) {
        stringRedisTemplate.delete(RedisKeys.REFRESH_TOKEN + userId);
    }

    // ==================== 用户级撤销水位（L-3 修复） ====================

    /**
     * 撤销某用户"从此刻之前"签发的全部 accessToken（改密时调用）。
     *
     * <p><b>为什么需要它</b>：jti 黑名单只能拉黑"当前那一枚"（登出语义）；改密时
     * 其他设备手里的 access 此前还能活到自然过期（≤30 分钟）—— "改密=把别人踢下线"
     * 只对 refresh 成立。记一个水位后，过滤器按 {@code issuedAt < 水位} 拒绝一整代 token，
     * 不需要改 token 格式（iat 本来就在签发时写入）。
     *
     * <p><b>TTL = access 有效期 + 60s</b>：水位只需活到"最后一个旧 token 过期"即可，
     * 过期后自动清理、不占内存；留 60s 余量防边界。与 jti 黑名单同一持久化前提
     * （生产 Redis 开 AOF；与现有机制保持同一权衡）。
     *
     * <p><b>为什么按秒且"严格小于"</b>：iat 是秒精度。用严格小于可保证"改密的同一秒
     * 里新登录签发的 token"不被误杀（代价是同一秒签发的旧 token 有 1 秒存活窗 ——
     * 实际不可利用，且当前请求的那一枚由 jti 黑名单兜住）。
     */
    public void revokeAccessBefore(Long userId, long epochSeconds) {
        stringRedisTemplate.opsForValue().set(
                RedisKeys.TOKEN_REVOKE_BEFORE + userId,
                String.valueOf(epochSeconds),
                accessTtlSeconds + 60, TimeUnit.SECONDS
        );
    }

    /** 该用户当前的撤销水位（epoch 秒）；没有则返回 null */
    public Long revokedBefore(Long userId) {
        String value = stringRedisTemplate.opsForValue().get(RedisKeys.TOKEN_REVOKE_BEFORE + userId);
        return value == null ? null : Long.parseLong(value);
    }
}
