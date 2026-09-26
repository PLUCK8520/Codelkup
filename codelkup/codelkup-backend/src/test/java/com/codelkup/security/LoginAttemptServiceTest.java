package com.codelkup.security;

import com.codelkup.common.constant.RedisKeys;
import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void recordFailure_incrementsBothDimensions_withTheirOwnWindows() {
        when(ops.increment(anyString())).thenReturn(1L);

        service.recordFailure(IP, EMAIL);

        verify(ops).increment(RedisKeys.LOGIN_FAIL_COUNT + IP + ":" + EMAIL);
        verify(ops).increment(RedisKeys.LOGIN_FAIL_BY_IP + IP);
        // 细粒度 15 分钟、粗粒度 10 分钟 —— 两个窗口不同，别写混
        verify(redis).expire(RedisKeys.LOGIN_FAIL_COUNT + IP + ":" + EMAIL, 15L, TimeUnit.MINUTES);
        verify(redis).expire(RedisKeys.LOGIN_FAIL_BY_IP + IP, 10L, TimeUnit.MINUTES);
    }

    @Test
    void recordFailure_doesNotRenewTtl_whenCounterAlreadyExists() {
        when(ops.increment(anyString())).thenReturn(3L);

        service.recordFailure(IP, EMAIL);

        // 每次都续期会让窗口永不结束（攻击者只需保持低频即可永久锁定别人）
        verify(redis, never()).expire(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class));
    }

    // ==================== 清除阶段：只清细粒度 ====================

    @Test
    void clearFailure_clearsOnlyEmailDimension() {
        service.clearFailure(IP, EMAIL);

        verify(redis).delete(RedisKeys.LOGIN_FAIL_COUNT + IP + ":" + EMAIL);
        // 成功登录不该抹掉 IP 维度的攻击痕迹，否则分片喷洒可用"自己账号登一次"清零
        verify(redis, never()).delete(RedisKeys.LOGIN_FAIL_BY_IP + IP);
        verify(redis, times(1)).delete(anyString());
    }
}