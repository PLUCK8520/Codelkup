package com.codelkup.security;

import com.codelkup.common.constant.RedisKeys;
import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li>但组合仍可被<b>分片</b>绕过：每次请求换一个邮箱、每个邮箱只试 4 次，
 *       永远不会累积到 5 次。因此再补一个<b>纯 IP 维度</b>的粗粒度上限（见 {@link #MAX_IP_ATTEMPTS}）</li>
 * </ul>
 *
 * @author 刘
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    /** 单账号（IP + 邮箱 维度）最大失败次数 */
    private static final int MAX_ATTEMPTS = 5;

    /** 单账号锁定时长（分钟） */
    private static final int LOCK_MINUTES = 15;

    /**
     * 纯 IP 维度的失败上限。
     *
     * <p>它拦的是"换邮箱分片"：没有这一层，攻击者每账号只试 4 次就能无限喷洒口令
     * （配合可批量确认邮箱是否存在，成本极低）。加上后，同一个出口 IP 的失败会累积。
     *
     * <p><b>代价要说清</b>：共享出口 IP（公司 / 校园 NAT）会互相影响 —— 30 次失败后
     * 该 IP 上所有人 10 分钟内都无法登录。之所以仍这样做：阈值取 30 次 / 10 分钟
     * （远高于正常用户手速），窗口也短，属于"两害相权"；而放任分片喷洒，
     * 等于防爆破机制形同不存在。
     */
    private static final int MAX_IP_ATTEMPTS = 30;

    /** 纯 IP 维度窗口（分钟） */
    private static final int IP_WINDOW_MINUTES = 10;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 纯 IP 维度上限（可配，默认 {@link #MAX_IP_ATTEMPTS}）。
     *
     * <p>字段初始化 + {@code @Value} 的组合是有意的：单测里不走 Spring 容器时
     * 字段保持默认值（不需要反射注入），生产则可由
     * {@code codelkup.rate-limit.login-ip-max} 覆盖。
     */
    @Value("${codelkup.rate-limit.login-ip-max:30}")
    private int maxIpAttempts = MAX_IP_ATTEMPTS;

    /** 细粒度 key：锁定"某 IP 打某账号" */
    private String key(String ip, String email) {
        return RedisKeys.LOGIN_FAIL_COUNT + ip + ":" + email;
    }

    /** 粗粒度 key：不绑定邮箱，专门用来发现"换邮箱分片" */
    private String ipKey(String ip) {
        return RedisKeys.LOGIN_FAIL_BY_IP + ip;
    }

    /**
     * 登录前检查是否已锁定。
     *
     * @throws BusinessException {@link ErrorCode#LOGIN_ATTEMPT_EXCEEDED} 单账号已锁定；
     *                           {@link ErrorCode#TOO_MANY_REQUESTS} 该 IP 失败次数已超上限
     */
    public void checkNotLocked(String ip, String email) {
        // 先判粗粒度（IP），再判细粒度（IP + 邮箱）：无论哪一层被拦，都不该再去做密码比对
        String ipCount = stringRedisTemplate.opsForValue().get(ipKey(ip));
        if (ipCount != null && Integer.parseInt(ipCount) >= maxIpAttempts) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "该网络登录失败次数过多，请 " + IP_WINDOW_MINUTES + " 分钟后再试");
        }

        String count = stringRedisTemplate.opsForValue().get(key(ip, email));
        if (count != null && Integer.parseInt(count) >= MAX_ATTEMPTS) {
            throw new BusinessException(ErrorCode.LOGIN_ATTEMPT_EXCEEDED,
                    "登录失败次数过多，请 " + LOCK_MINUTES + " 分钟后再试");
        }
    }

    /** 登录失败时累加计数（两个维度都要记） */
    public void recordFailure(String ip, String email) {
        increment(key(ip, email), LOCK_MINUTES);
        increment(ipKey(ip), IP_WINDOW_MINUTES);
    }

    /**
     * 登录成功时清除计数。
     *
     * <p>只清细粒度 key（"我这次成功了"），<b>不清</b>纯 IP 计数 —— 否则攻击者
     * 只要手里有一个自己的正常账号，成功登录一次就能把 IP 维度的失败记录抹掉，
     * 那一层防线等于白加。
     */
    public void clearFailure(String ip, String email) {
        stringRedisTemplate.delete(key(ip, email));
    }

    /** INCR 并在首次计数时设置过期（每次都续期会导致"永远锁定"） */
    private void increment(String key, int windowMinutes) {
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, windowMinutes, TimeUnit.MINUTES);
        }
    }
}