package com.codelkup.module.user.service;

import com.codelkup.module.user.event.ReputationEvent;
import com.codelkup.module.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 声望结算的单元测试。
 *
 * <p>钉死四件事：
 * <ol>
 *   <li><b>等级阈值边界</b> —— 49/50、199/200 这类临界值是升级规则最容易写错的地方</li>
 *   <li><b>等级基于"读回的声望"推导</b>，不是本地猜的值（并发下猜的值会偏）</li>
 *   <li><b>加分与减分对称</b>（+2/-2、+3/-3、+15/-15）—— 不对称就能刷分</li>
 *   <li><b>失败不重抛</b> —— 声望结算失败绝不能让点赞/采纳这类主流程回滚</li>
 * </ol>
 */
class ReputationServiceTest {

    private static final Long USER = 1L;

    private UserMapper userMapper;
    private ReputationService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        service = new ReputationService(userMapper);
    }

    // ==================== 等级映射 ====================

    @Test
    void levelOf_boundaries() {
        assertEquals(1, ReputationService.levelOf(0));
        assertEquals(1, ReputationService.levelOf(49));
        assertEquals(2, ReputationService.levelOf(50));
        assertEquals(2, ReputationService.levelOf(199));
        assertEquals(3, ReputationService.levelOf(200));
        assertEquals(3, ReputationService.levelOf(499));
        assertEquals(4, ReputationService.levelOf(500));
        assertEquals(4, ReputationService.levelOf(999));
        assertEquals(5, ReputationService.levelOf(1000));
        assertEquals(5, ReputationService.levelOf(99999));
    }

    // ==================== 结算行为 ====================

    @Test
    void apply_syncsLevelFromValueReadBackFromDb() {
        // 关键：等级用的是"数据库读回的值"（63），不是本地"旧值 + delta"的猜测
        when(userMapper.selectReputation(USER)).thenReturn(63);

        service.apply(USER, 15, "回答被采纳");

        verify(userMapper).addReputation(USER, 15);
        verify(userMapper).updateLevel(USER, 2);
    }

    @Test
    void apply_crossesThreshold_upgradesLevel() {
        when(userMapper.selectReputation(USER)).thenReturn(48, 51);

        service.apply(USER, 15, "回答被采纳");
        service.apply(USER, 3, "被收藏");

        verify(userMapper).updateLevel(USER, 1); // 48 → Lv.1
        verify(userMapper).updateLevel(USER, 2); // 51 → Lv.2（跨过 50 的阈值）
    }

    @Test
    void eventSequence_accumulatesAsExpected() {
        // 事件序列：被赞(+2) → 被收藏(+3) → 回答被采纳(+15) = 20 分，仍在 Lv.1
        // （"一次被采纳不足以升级"是阈值表的有意设计）
        when(userMapper.selectReputation(USER)).thenReturn(2, 5, 20);

        service.onReputationChanged(ReputationEvent.liked(USER));
        service.onReputationChanged(ReputationEvent.collected(USER));
        service.onReputationChanged(ReputationEvent.accepted(USER));

        verify(userMapper).addReputation(USER, 2);
        verify(userMapper).addReputation(USER, 3);
        verify(userMapper).addReputation(USER, 15);
        verify(userMapper, times(3)).updateLevel(USER, 1);
    }

    @Test
    void negativeEvents_areSymmetric() {
        // 取消点赞/取消收藏/采纳改判必须是负数：只加不减等于留了刷分口子
        assertEquals(-2, ReputationEvent.unliked(USER).delta());
        assertEquals(-3, ReputationEvent.uncollected(USER).delta());
        assertEquals(-15, ReputationEvent.acceptRevoked(USER).delta());

        when(userMapper.selectReputation(USER)).thenReturn(0);
        service.onReputationChanged(ReputationEvent.unliked(USER));
        verify(userMapper).addReputation(USER, -2);
    }

    // ==================== 健壮性 ====================

    @Test
    void apply_ignoresNullUserAndZeroDelta() {
        service.apply(null, 5, "被点赞");
        service.apply(USER, 0, "无变化");

        verifyNoInteractions(userMapper);
    }

    @Test
    void apply_swallowsFailure_soBusinessTransactionStaysAlive() {
        when(userMapper.addReputation(anyLong(), anyInt()))
                .thenThrow(new RuntimeException("t_user 锁等待超时"));

        assertDoesNotThrow(() -> service.apply(USER, 2, "被点赞"));

        // 失败后不应继续去同步等级（没有可信的声望值可用）
        verify(userMapper, never()).updateLevel(anyLong(), anyInt());
    }
}