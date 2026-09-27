package com.codelkup.security;

import com.codelkup.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 计数器式限流（固定窗口）。
 *
 * <p>用途：给"按设计就必须泄露一点信息"的接口兜底。首个使用方是
 * {@code GET /api/auth/check-email} —— 它天然回答"某邮箱是否已注册"，
 * 不限流就等于开放了一个可批量枚举站内邮箱的查询口子。
 *
 * <h2>为什么是固定窗口而不是滑动窗口 / 令牌桶</h2>
 * 这里的目标只是把批量枚举的成本抬到不可行（几十次/十分钟量级足以做到），
 * 不需要精确控制突发流量。固定窗口只需一次自增计数；为让"计数 + 过期"不可分割、
 * 并**自愈异常残留的无 TTL 键**（F3，第二轮审计：INCR 与 EXPIRE 分离时，
 * 首请求的 EXPIRE 失败会让该桶永久 42901），用一小段 Lua 原子完成 ——
 * 仍是固定窗口，不是滑动窗口，没有引入 ZSet 的复杂度。
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
     * 原子"计数 + 过期"（F3）：INCR 与 EXPIRE 必须一次完成；
     * TTL&lt;0 时补设 —— 既覆盖首请求，也**自愈**历史异常残留的无 TTL 键
     * （BRPOP/连接中断等都可能在 INCR 成功后丢掉 EXPIRE）。
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

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
            Long count = stringRedisTemplate.execute(
                    ACQUIRE_SCRIPT, List.of(key), String.valueOf(windowMinutes * 60L));
            if (count == null) {
                return true;
            }
            return count <= max;
        } catch (Exception e) {
            log.warn("限流计数失败，放行本次请求 | key={} | err={}", key, e.getMessage());
            return true;
        }
    }
}