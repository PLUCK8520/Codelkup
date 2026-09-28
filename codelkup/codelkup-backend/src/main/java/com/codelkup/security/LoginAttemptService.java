package com.codelkup.security;

import com.codelkup.common.constant.RedisKeys;
import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

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

    /**
     * 纯邮箱维度的失败上限（跨 IP 累计）—— 第五轮审计 #1 补。
     *
     * <p>为什么还需要它：IP 维度的两个桶全部以"客户端 IP"为键，而 IP 来自
     * {@code getRemoteAddr()}；一旦部署拓扑被绕过（如后端可被私网直连、伪造
     * X-Forwarded-For），攻击者可以为每次请求"造"一个新 IP，把 IP 桶与
     * IP+邮箱桶都重置掉，只剩"定向爆破某账号"这一条路仍然打得通。
     *
     * <p>为什么不干脆用"5 次 / 15 分钟"锁邮箱：那会打开"恶意锁死他人账号"的
     * 拒绝服务面（知道某人邮箱就能锁他）。因此这里取<b>宽松上限 + 长窗口</b>：
     * 15 次 / 60 分钟 —— 正常用户极少在一个小时内输错 15 次密码，
     * 而跨 IP 的定向爆破会被稳稳限速。
     */
    private static final int MAX_EMAIL_ATTEMPTS = 15;

    /** 纯邮箱维度窗口（分钟）：比 IP 桶长，因为它是跨 IP 追同一账号的唯一兜底 */
    private static final int EMAIL_WINDOW_MINUTES = 60;

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

    /** 纯邮箱维度上限（可配，默认 {@link #MAX_EMAIL_ATTEMPTS}），注入方式同 {@link #maxIpAttempts} */
    @Value("${codelkup.rate-limit.login-email-max:15}")
    private int maxEmailAttempts = MAX_EMAIL_ATTEMPTS;

    /** 细粒度 key：锁定"某 IP 打某账号" */
    private String key(String ip, String email) {
        return RedisKeys.LOGIN_FAIL_COUNT + ip + ":" + email;
    }

    /** 粗粒度 key：不绑定邮箱，专门用来发现"换邮箱分片" */
    private String ipKey(String ip) {
        return RedisKeys.LOGIN_FAIL_BY_IP + ip;
    }

    /**
     * 邮箱维度 key：跨 IP 累计"针对同一账号的失败"。
     * 归属段写法（前缀 + {@code email:}）与改密锁定的 {@code pwd:} 同型，
     * 不新增 RedisKeys 常量；清限流桶的工具只清 {@code rate:*}，不会误删它。
     */
    private String emailKey(String email) {
        return RedisKeys.LOGIN_FAIL_COUNT + "email:" + email;
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

        // 再判"纯邮箱"（跨 IP）：防伪造 IP 重置前两层 —— 详见 MAX_EMAIL_ATTEMPTS 的注释
        String emailCount = stringRedisTemplate.opsForValue().get(emailKey(email));
        if (emailCount != null && Integer.parseInt(emailCount) >= maxEmailAttempts) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "该账号登录失败次数过多，请 " + EMAIL_WINDOW_MINUTES + " 分钟后再试");
        }

        String count = stringRedisTemplate.opsForValue().get(key(ip, email));
        if (count != null && Integer.parseInt(count) >= MAX_ATTEMPTS) {
            throw new BusinessException(ErrorCode.LOGIN_ATTEMPT_EXCEEDED,
                    "登录失败次数过多，请 " + LOCK_MINUTES + " 分钟后再试");
        }
    }

    /** 登录失败时累加计数（三个维度都要记：IP / 邮箱 / IP+邮箱） */
    public void recordFailure(String ip, String email) {
        increment(key(ip, email), LOCK_MINUTES);
        increment(ipKey(ip), IP_WINDOW_MINUTES);
        increment(emailKey(email), EMAIL_WINDOW_MINUTES);
    }

    /**
     * 登录成功时清除计数。
     *
     * <p>清两个 key：细粒度（IP+邮箱，"我这次成功了"）与<b>纯邮箱</b>
     * （账号本人自证，跨 IP 的失败痕迹可以清零，避免正常用户被累计数误锁）。
     *
     * <p><b>不清</b>纯 IP 计数 —— 否则攻击者只要手里有一个自己的正常账号，
     * 成功登录一次就能把 IP 维度的失败记录抹掉，那一层防线等于白加。
     * （纯邮箱桶没有这个问题：它按"被尝试的账号"记，攻击者拿自己的账号刷不到别人的桶。）
     */
    public void clearFailure(String ip, String email) {
        stringRedisTemplate.delete(key(ip, email));
        stringRedisTemplate.delete(emailKey(email));
    }

    /* ==================== 改密失败锁定（M-1 修复：userId 维度） ==================== */

    /**
     * 改密失败计数的 key：沿用登录失败的前缀、换成 userId 维度。
     *
     * <p>为什么是 userId：改密接口要求**先持有该账号的有效凭证**，
     * 攻击面是"拿到凭证后爆破原密码"—— 绑定 userId 最精确；也天然不会像
     * "按邮箱锁定"那样被利用来 DoS 他人（能触发锁定的只有该账号的持有者）。
     * 放在 {@code codelkup:auth:} 命名空间下：清限流桶的工具只清 {@code rate:*}，
     * 不会误删它 —— 与登录锁定同一待遇。
     */
    private String pwdChangeKey(Long userId) {
        return RedisKeys.LOGIN_FAIL_COUNT + "pwd:" + userId;
    }

    /**
     * 改密前检查是否已锁定（阈值与登录一致：{@link #MAX_ATTEMPTS} 次 / {@link #LOCK_MINUTES} 分钟）。
     *
     * <p>锁定期间**即使原密码正确也不放行**（检查先于比对）—— 这正是"锁定"的含义，
     * 与 login 的顺序一致。
     *
     * @throws BusinessException {@link ErrorCode#LOGIN_ATTEMPT_EXCEEDED}
     */
    public void checkPasswordChangeNotLocked(Long userId) {
        String count = stringRedisTemplate.opsForValue().get(pwdChangeKey(userId));
        if (count != null && Integer.parseInt(count) >= MAX_ATTEMPTS) {
            throw new BusinessException(ErrorCode.LOGIN_ATTEMPT_EXCEEDED,
                    "原密码错误次数过多，请 " + LOCK_MINUTES + " 分钟后再试");
        }
    }

    /** 改密"原密码不正确"时累加（只有凭证比对失败才计数 —— 与登录同一口径） */
    public void recordPasswordChangeFailure(Long userId) {
        increment(pwdChangeKey(userId), LOCK_MINUTES);
    }

    /** 改密成功时清除计数（"成功一次即清零"，与登录一致） */
    public void clearPasswordChangeFailure(Long userId) {
        stringRedisTemplate.delete(pwdChangeKey(userId));
    }

    /**
     * 计数脚本：INCR + 补过期，用 Lua 保证原子。
     *
     * <p>修复点：旧实现分两条命令 —— {@code INCR} 成功、仅当 {@code count == 1} 时
     * 才 {@code EXPIRE}。若 EXPIRE 那一步丢失（连接中断 / 进程崩溃 / 网络抖动），
     * 这个 key 就<b>永久不过期</b>：用户被永久锁死，且没有任何自愈入口。
     *
     * <p>放进一段 Lua 后由 Redis 单线程执行，两条命令同生共死；判定条件也改成
     * "TTL 为负才补"（而不是"仅首次"）—— 即便历史上已经留下过无过期的 key，
     * 也会在下一次计数时自动补上过期时间。
     */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL_SCRIPT = new DefaultRedisScript<>(
            """
            local count = redis.call('INCR', KEYS[1])
            if redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    /** INCR 并保证有过期（"每次续期导致永不锁定"不会发生：只在 TTL 为负时才设置） */
    private void increment(String key, int windowMinutes) {
        stringRedisTemplate.execute(
                INCR_WITH_TTL_SCRIPT, List.of(key), String.valueOf(windowMinutes * 60L));
    }
}