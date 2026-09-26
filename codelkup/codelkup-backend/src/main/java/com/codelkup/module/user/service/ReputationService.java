package com.codelkup.module.user.service;

import com.codelkup.module.user.event.ReputationEvent;
import com.codelkup.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 声望与等级结算服务。
 *
 * <h2>为什么需要它</h2>
 * {@code t_user.reputation} 与 {@code t_user.level} 此前是<b>死字段</b> ——
 * 注册时写 0 / 1，之后再没有代码碰过它们。用户主页上的"声望 / 等级"因此是个摆设。
 *
 * <h2>与通知/计数监听器最大的不同：这里<b>不用</b> @Async、也不用 AFTER_COMMIT</h2>
 * <ul>
 *   <li><b>不用 @Async</b>：声望是用户能立刻看到的数字。异步化会留出"还没更新"的窗口，
 *       表现为"点赞成功但主页声望没变"，看起来就像功能坏了。</li>
 *   <li><b>用 BEFORE_COMMIT 而不是 AFTER_COMMIT</b>：声望变化与业务动作
 *       （点赞、采纳）必须在同一个事务里同生共死 —— 点赞回滚了，声望不能已经加上去。
 *       AFTER_COMMIT 在提交后另起事务，做不到这一点。</li>
 *   <li><b>内部吞异常</b>：结算失败只记 WARN，不让"点赞"因为声望更新失败而失败
 *       （与计数/通知的取舍一致：辅助数据的失败不该升级成主流程失败）。</li>
 * </ul>
 *
 * <h2>等级是派生值</h2>
 * 等级完全由声望决定（阈值表见 {@link #levelOf}），所以每次结算后重新推导，
 * 不单独维护一套"升级/降级"逻辑 —— 那只会多出一份可能与声望不一致的状态。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReputationService {

    /**
     * 等级阈值表（升序，单位：声望）。
     *
     * <p>取值考虑：新用户 0 分即 Lv.1；一次被采纳（+15）不足以升级，
     * 约 4 次被采纳（60 分）到 Lv.2，形成"有内容被别人认可"的门槛；
     * Lv.5（1000 分）是长期活跃贡献者的量级。
     */
    private static final int[] LEVEL_THRESHOLDS = {0, 50, 200, 500, 1000};

    private final UserMapper userMapper;

    /**
     * 声望 → 等级。纯函数，便于单测把所有边界钉死。
     */
    public static int levelOf(int reputation) {
        int level = 1;
        for (int i = 0; i < LEVEL_THRESHOLDS.length; i++) {
            if (reputation >= LEVEL_THRESHOLDS[i]) {
                level = i + 1;
            }
        }
        return level;
    }

    /**
     * 事件入口：在主事务提交前完成结算。
     *
     * <p>{@code fallbackExecution = true}：没有活跃事务时也执行，
     * 避免将来某个调用方漏掉 {@code @Transactional} 导致声望"静默不结算"。
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onReputationChanged(ReputationEvent event) {
        apply(event.userId(), event.delta(), event.reason());
    }

    /**
     * 结算一次声望变化。
     *
     * <p>三个步骤的顺序不能换：
     * <ol>
     *   <li>{@code addReputation} —— 用 SQL 原子自增（不是"读出 + 计算 + 写回"，
     *       并发点赞会丢更新），并用 {@code GREATEST(..., 0)} 夹住下界</li>
     *   <li>读回自增后的真实值 —— 等级必须基于它推导，不能基于本地猜测的值</li>
     *   <li>写等级 —— 派生字段，允许"滞后一拍"（即便这一步失败，下次结算也会修正）</li>
     * </ol>
     */
    public void apply(Long userId, int delta, String reason) {
        if (userId == null || delta == 0) {
            return;
        }
        try {
            userMapper.addReputation(userId, delta);
            Integer reputation = userMapper.selectReputation(userId);
            if (reputation != null) {
                userMapper.updateLevel(userId, levelOf(reputation));
            }
            log.info("声望结算 | userId={} | delta={} | reason={} | 结算后声望={}",
                    userId, delta, reason, reputation);
        } catch (Exception e) {
            // 不重抛：声望是辅助数据，不能让它把点赞/采纳这类主流程带崩
            log.warn("声望结算失败（不影响主流程）| userId={} | delta={} | reason={} | err={}",
                    userId, delta, reason, e.getMessage());
        }
    }
}