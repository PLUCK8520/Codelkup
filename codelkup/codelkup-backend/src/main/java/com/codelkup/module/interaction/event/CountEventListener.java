package com.codelkup.module.interaction.event;

import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.interaction.mapper.CommentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 计数事件监听器 —— 把 MySQL 基线列的更新异步化。
 *
 * <h2>为什么用 {@link TransactionalEventListener} 而不是 {@code @EventListener}</h2>
 * 发布事件时所在的业务事务（比如"插入点赞记录"）可能还在进行中。
 * 若用 {@code @EventListener}，异步线程可能在主事务提交<b>之前</b>就去读
 * {@code t_user_action} 做统计，读到的是旧数据；更糟的是主事务若回滚，
 * 计数却已经加过了，产生永久不一致。
 *
 * <p>{@code TransactionalEventListener(phase = AFTER_COMMIT)} 保证：
 * <b>主事务确认提交后</b>才触发计数记账。回滚时事件被丢弃，不会误加计数。
 *
 * <h2>这里的 MySQL 更新是什么角色</h2>
 * 它是<b>可重建的基线</b>，不是实时值。用户在页面上立刻看到的数字来自
 * Redis（请求线程已 {@code INCR} 过），这里只是把这笔账持久化下来。
 * 所以即使这里失败、延迟、甚至丢几次，跑重建脚本就能修正 ——
 * 这正是"基线 + 增量"模型的价值。
 *
 * @author 刘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CountEventListener {

    private final PostMapper postMapper;
    private final CommentMapper commentMapper;

    /**
     * 异步更新 MySQL 基线列。
     *
     * <p>⚠️ 任何异常都必须在这里吞掉：异步方法的异常不会传回请求线程，
     * 若不捕获只会打一行堆栈，"计数悄悄错了"却没人发现。这里显式记 warn，
     * 并由重建脚本兜底（重建脚本会比对 Redis 与 MySQL，发现偏差即修正）。
     */
    @Async
    @TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onCountChanged(CountChangedEvent event) {
        try {
            switch (event.dimension()) {
                case "like" -> postMapper.addLikeCount(event.targetId(), (int) event.delta());
                case "collect" -> postMapper.addCollectCount(event.targetId(), (int) event.delta());
                case "comment" -> postMapper.addCommentCount(event.targetId(), (int) event.delta());
                case "comment_like" -> commentMapper.addLikeCount(event.targetId(), (int) event.delta());
                default -> log.warn("未知计数维度，忽略 | dimension={}", event.dimension());
            }
        } catch (Exception e) {
            // 不重抛：异步线程里重抛没有任何接收方，只会丢失上下文
            log.warn("MySQL 基线更新失败（可由重建脚本修正）| dimension={} | targetId={} | delta={} | err={}",
                    event.dimension(), event.targetId(), event.delta(), e.getMessage());
        }
    }
}
