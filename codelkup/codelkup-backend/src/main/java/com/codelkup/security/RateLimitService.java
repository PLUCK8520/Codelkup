package com.codelkup.security;

import com.codelkup.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 计数器式限流（固定窗口）。
 *
 * <p>用途：给"按设计就必须泄露一点信息"的接口兜底。首个使用方是
 * {@code GET /api/auth/check-email} —— 它天然回答"某邮箱是否已注册"，
 * 不限流就等于开放了一个可批量枚举站内邮箱的查询口子。
 *
 * <h2>为什么是固定窗口而不是滑动窗口 / 令牌桶</h2>
 * 这里的目标只是把批量枚举的成本抬到不可行（几十次/十分钟量级足以做到），
 * 不需要精确控制突发流量。固定窗口只要 {@code INCR + EXPIRE} 两条命令，
 * 无第三方依赖、行为一眼可读懂；滑动窗口要多一个 ZSet 或 Lua，
 * 对本项目是纯粹的复杂度负担。
 *
 * <h2>降级策略</h2>
 * Redis 不可用时<b>放行</b>而不是拒绝：限流是保护措施，不该在依赖故障时
 * 变成"功能全挂"的新故障点（与 CounterService / LoginAttemptService 的降级口径一致）。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试获取一次配额。
     *
     * @param bucket        业务桶名（如 {@code check_email}），用于隔离不同接口的计数
     * @param identity      限流维度（这里传客户端 IP）
     * @param max           窗口内允许的最大次数
     * @param windowMinutes 窗口长度（分钟）
     * @return 允许通过返回 true；超出配额返回 false
     */
    public boolean tryAcquire(String bucket, String identity, int max, int windowMinutes) {
        String key = RedisKeys.RATE_LIMIT + bucket + ":" + identity;
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count == null) {
                return true;
            }
            // 只在首次计数时设置过期，避免每次请求都续期导致"窗口永不过期"
            if (count == 1L) {
                stringRedisTemplate.expire(key, windowMinutes, TimeUnit.MINUTES);
            }
            return count <= max;
        } catch (Exception e) {
            log.warn("限流计数失败，放行本次请求 | key={} | err={}", key, e.getMessage());
            return true;
        }
    }
}