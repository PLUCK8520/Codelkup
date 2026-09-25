package com.codelkup.module.interaction.event;

/**
 * 计数变更事件。
 *
 * <p><b>为什么用事件而不是直接调用</b>：写路径要做三件事——
 * 写 {@code t_user_action}、更新 Redis 计数、更新 MySQL 基线列。
 * 前两件必须在请求线程内完成（用户要立刻看到计数变化），第三件是"记账"，
 * 晚几十毫秒无所谓。用事件把它异步出去，请求 RT 不被 MySQL 写入拖慢。
 *
 * <p>⚠️ 这里刻意<b>不做批量缓冲</b>（DEV-DOC 修订 D2）：原文档同一节里
 * 既写"@Async 内存批量 flush"又写"Redis List 做写缓冲"，两套方案自相矛盾。
 * MVP 决定就是 {@code @Async} 直接落库。理由：
 * <ol>
 *   <li>本项目量级无并发压力，批量缓冲是过早优化</li>
 *   <li>批量缓冲会引入"进程重启丢内存增量"的一致性问题，反而更复杂</li>
 *   <li>留下优化空间本身就是 V0.2 的叙事素材</li>
 * </ol>
 *
 * @param dimension 计数维度：like / collect / comment
 * @param targetId  目标 ID（帖子 ID 或评论 ID）
 * @param delta     增量，可为负
 * @author 刘
 */
public record CountChangedEvent(String dimension, Long targetId, long delta) {

    /** 帖子点赞维度 */
    public static CountChangedEvent postLike(Long postId, long delta) {
        return new CountChangedEvent("like", postId, delta);
    }

    /** 帖子收藏维度 */
    public static CountChangedEvent postCollect(Long postId, long delta) {
        return new CountChangedEvent("collect", postId, delta);
    }

    /** 帖子评论数维度 */
    public static CountChangedEvent postComment(Long postId, long delta) {
        return new CountChangedEvent("comment", postId, delta);
    }

    /** 评论点赞维度 */
    public static CountChangedEvent commentLike(Long commentId, long delta) {
        return new CountChangedEvent("comment_like", commentId, delta);
    }
}
