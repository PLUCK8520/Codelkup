package com.codelkup.security;

import com.codelkup.common.constant.RedisKeys;
import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 登录失败计数与限流。
 *
 * <p>防御目标：<b>撞库 / 暴力破解</b>。按 {@code IP + 邮箱} 维度计数，
 * 达到阈值后锁定一段时间。
 *
 * <p>为什么按 IP + 邮箱双维度而不是只用其中一个？
 * <ul>
 *   <li>只用 IP —— 多台机器轮换 IP 即可绕过</li>
 *   <li>只用邮箱 —— 攻击者可恶意锁死他人账号（拒绝服务）</li>
 *   <li>两者组合 —— 同一 IP 打同一账号才计数，既防爆破又不至于轻易被用来锁死账号</li>
 * </ul>
 *
 * @author 刘
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    /** 最大失败次数 */
    private static final int MAX_ATTEMPTS = 5;

    /** 锁定时长（分钟） */
    private static final int LOCK_MINUTES = 15;

    private final StringRedisTemplate stringRedisTemplate;

    private String key(String ip, String email) {
        return RedisKeys.LOGIN_FAIL_COUNT + ip + ":" + email;
    }

    /**
     * 登录前检查是否已锁定。
     *
     * @throws BusinessException 传入 {@link ErrorCode#LOGIN_ATTEMPT_EXCEEDED} 表示已锁定
     */
    public void checkNotLocked(String ip, String email) {
        String count = stringRedisTemplate.opsForValue().get(key(ip, email));
        if (count != null && Integer.parseInt(count) >= MAX_ATTEMPTS) {
            throw new BusinessException(ErrorCode.LOGIN_ATTEMPT_EXCEEDED,
                    "登录失败次数过多，请 " + LOCK_MINUTES + " 分钟后再试");
        }
    }

    /** 登录失败时累加计数 */
    public void recordFailure(String ip, String email) {
        String k = key(ip, email);
        Long count = stringRedisTemplate.opsForValue().increment(k);
        // 首次失败时才设置过期，避免每次失败都续期导致"永远锁定"
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(k, LOCK_MINUTES, TimeUnit.MINUTES);
        }
    }

    /** 登录成功时清除计数 */
    public void clearFailure(String ip, String email) {
        stringRedisTemplate.delete(key(ip, email));
    }
}
