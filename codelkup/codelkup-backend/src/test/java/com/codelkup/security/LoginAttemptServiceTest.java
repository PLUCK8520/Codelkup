package com.codelkup.security;

import com.codelkup.common.constant.RedisKeys;
import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录失败锁定的双维度行为测试。
 *
 * <p>为什么值得钉死：这层机制此前被"伪造 X-Forwarded-For 换计数键"绕过一次，
 * 修复后又发现"每次换邮箱、每账号只试 4 次"的分片绕过。两个维度的边界
 * （阈值、窗口、谁清谁不清）都容易在后续改动里被无意破坏，所以用单测固定。
 */
class LoginAttemptServiceTest {

    private static final String IP = "203.0.113.7";
    private static final String EMAIL = "victim@example.com";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private LoginAttemptService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new LoginAttemptService(redis);
    }

    // ==================== 检查阶段：两个维度的边界 ====================

    @Test
    void ipDimension_blocksAtThreshold() {
        when(ops.get(RedisKeys.LOGIN_FAIL_BY_IP + IP)).thenReturn("30");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.checkNotLocked(IP, EMAIL));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS, e.getErrorCode());
    }

    @Test
    void ipDimension_underThreshold_passes() {
        when(ops.get(RedisKeys.LOGIN_FAIL_BY_IP + IP)).thenReturn("29");
        when(ops.get(RedisKeys.LOGIN_FAIL_COUNT + IP + ":" + EMAIL)).thenReturn(null);

        assertDoesNotThrow(() -> service.checkNotLocked(IP, EMAIL));
    }

    @Test
    void pureEmailDimension_blocksAtThreshold_acrossIps() {
        // 第五轮审计 #1：伪造 IP（每次换新键）时，纯邮箱维度必须仍然拦得住
        when(ops.get(RedisKeys.LOGIN_FAIL_BY_IP + "1.2.3.4")).thenReturn(null);
        when(ops.get(RedisKeys.LOGIN_FAIL_COUNT + "email:" + EMAIL)).thenReturn("15");
        when(ops.get(RedisKeys.LOGIN_FAIL_COUNT + "1.2.3.4" + ":" + EMAIL)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.checkNotLocked("1.2.3.4", EMAIL));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS, e.getErrorCode());
    }

    @Test
    void emailDimension_blocksAtThreshold() {
        when(ops.get(RedisKeys.LOGIN_FAIL_BY_IP + IP)).thenReturn(null);
        when(ops.get(RedisKeys.LOGIN_FAIL_COUNT + IP + ":" + EMAIL)).thenReturn("5");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.checkNotLocked(IP, EMAIL));

        assertEquals(ErrorCode.LOGIN_ATTEMPT_EXCEEDED, e.getErrorCode());
    }

    @Test
    void bothDimensions_empty_doesNotBlock() {
        when(ops.get(anyString())).thenReturn(null);

        assertDoesNotThrow(() -> service.checkNotLocked(IP, EMAIL));
    }

    // ==================== 记录阶段：两个维度都要计数 ====================

    /**
     * 捕获 {@code execute(...)} 的调用参数并让脚本返回 1。
     *
     * <p>返回列表的每个元素是一次调用的参数数组：{@code [0]=script}、{@code [1]=keys}、
     * 之后是展开的变长参数（本类实现只传一个参数 —— TTL 秒数，
     * 所以取 {@code args[args.length - 1]} 即它）。
     */
    private List<Object[]> captureIncrements() {
        List<Object[]> calls = new ArrayList<>();
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenAnswer(inv -> {
            calls.add(inv.getArguments());
            return 1L;
        });
        return calls;
    }

    @Test
    void recordFailure_incrementsAllThreeDimensions_withTheirOwnWindows() {
        List<Object[]> calls = captureIncrements();

        service.recordFailure(IP, EMAIL);

        // ★ 实现已改为"INCR + 补过期"的单条 Lua（旧实现分两条命令，EXPIRE 丢失会永久锁死）。
        //   这里钉三件事：三个 key 都被计数、窗口秒数没有写混
        //   （IP+邮箱 15min=900s / IP 10min=600s / 邮箱 60min=3600s，第五轮审计 #1 补）。
        Map<String, String> ttlByKey = new HashMap<>();
        for (Object[] args : calls) {
            ttlByKey.put(((List<?>) args[1]).get(0).toString(),
                    args[args.length - 1].toString());
        }
        assertEquals(Map.of(
                RedisKeys.LOGIN_FAIL_COUNT + IP + ":" + EMAIL, "900",
                RedisKeys.LOGIN_FAIL_BY_IP + IP, "600",
                RedisKeys.LOGIN_FAIL_COUNT + "email:" + EMAIL, "3600"), ttlByKey);
        // Java 侧不再直接 EXPIRE：过期由 Lua 在同一次原子执行里完成
        verify(redis, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void recordFailure_neverRenewsTtlFromJavaSide() {
        // 续期判定已下沉到 Lua（只在 TTL < 0 时补 EXPIRE，实现"自愈"而不是"续期"）——
        // Java 侧一旦出现直接 expire()，就意味着"每次失败都续期 → 窗口永不结束"回归了
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(3L);

        service.recordFailure(IP, EMAIL);

        verify(redis, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    // ==================== 清除阶段：清"账号绑定"键，不清 IP 维度 ====================

    @Test
    void clearFailure_clearsBothAccountBindings_notIpDimension() {
        service.clearFailure(IP, EMAIL);

        // 清两个"账号绑定"键：IP+邮箱、纯邮箱（本人自证，防跨 IP 累计误锁 —— 审计 #1）
        verify(redis).delete(RedisKeys.LOGIN_FAIL_COUNT + IP + ":" + EMAIL);
        verify(redis).delete(RedisKeys.LOGIN_FAIL_COUNT + "email:" + EMAIL);
        // 成功登录不该抹掉 IP 维度的攻击痕迹，否则分片喷洒可用"自己账号登一次"清零
        verify(redis, never()).delete(RedisKeys.LOGIN_FAIL_BY_IP + IP);
        verify(redis, times(2)).delete(anyString());
    }

    // ==================== 改密失败锁定（M-1 修复：userId 维度） ====================

    private static final String PWD_KEY_42 = RedisKeys.LOGIN_FAIL_COUNT + "pwd:42";

    @Test
    void pwdChange_blocksAtThreshold() {
        when(ops.get(PWD_KEY_42)).thenReturn("5");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.checkPasswordChangeNotLocked(42L));

        assertEquals(ErrorCode.LOGIN_ATTEMPT_EXCEEDED, e.getErrorCode());
    }

    @Test
    void pwdChange_underThreshold_passes() {
        when(ops.get(PWD_KEY_42)).thenReturn("4");

        assertDoesNotThrow(() -> service.checkPasswordChangeNotLocked(42L));
    }

    @Test
    void pwdChange_recordFailure_usesUserDimension_withLockWindow() {
        List<Object[]> calls = captureIncrements();

        service.recordPasswordChangeFailure(42L);

        // 只记 userId 一个维度：不能顺带污染登录的 IP/邮箱计数键
        assertEquals(1, calls.size());
        assertEquals(List.of(PWD_KEY_42), calls.get(0)[1]);
        // 窗口 15 分钟 = 900 秒（与登录锁定同一口径）
        assertEquals("900", calls.get(0)[calls.get(0).length - 1].toString());
    }

    @Test
    void pwdChange_clearFailure_deletesOnlyItsKey() {
        service.clearPasswordChangeFailure(42L);

        verify(redis).delete(PWD_KEY_42);
        verify(redis, times(1)).delete(anyString());
    }
}