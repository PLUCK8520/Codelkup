package com.codelkup.module.user.event;

/**
 * 声望事件。
 *
 * <h2>为什么声望也走事件</h2>
 * 与 {@code NotificationEvent} 同一套理由（模块解耦 + 触发点集中可审计），
 * 但有一点关键差异：<b>监听器不是异步的，而是在同一事务内结算</b> ——
 * 声望是"用户能立刻看到的数字"，行内同步结算才能保证"点赞成功"的响应返回时，
 * 作者主页上的声望也已经变了；异步化会留出一个"数字还没更新"的窗口，
 * 让验收脚本与用户都以为没生效。详见 {@code ReputationService}。
 *
 * <h2>为什么区分"加/减"两个方向</h2>
 * 只加不减会留下刷分漏洞：点赞 → 取消 → 再点赞 可以无限刷。
 * 因此每个加分动作都有对称的减分动作，且 {@code accept} 的改判也有回收
 * （见 {@link #acceptRevoked}）。
 *
 * @param userId 声望变化的人
 * @param delta  变化量（正负）
 * @param reason 变化原因（只用于日志与排查，不入库）
 * @author 刘
 */
public record ReputationEvent(Long userId, int delta, String reason) {

    /** 帖子/评论被点赞：+2 */
    public static ReputationEvent liked(Long authorId) {
        return new ReputationEvent(authorId, 2, "被点赞");
    }

    /** 取消点赞：-2（与 {@link #liked} 对称，否则连点可刷分） */
    public static ReputationEvent unliked(Long authorId) {
        return new ReputationEvent(authorId, -2, "被取消点赞");
    }

    /** 帖子被收藏：+3 */
    public static ReputationEvent collected(Long authorId) {
        return new ReputationEvent(authorId, 3, "被收藏");
    }

    /** 取消收藏：-3 */
    public static ReputationEvent uncollected(Long authorId) {
        return new ReputationEvent(authorId, -3, "被取消收藏");
    }

    /** 回答被采纳：+15（权重最高 —— 解决问题才是社区的核心价值） */
    public static ReputationEvent accepted(Long authorId) {
        return new ReputationEvent(authorId, 15, "回答被采纳");
    }

    /**
     * 采纳被改判：-15。
     *
     * <p>没有这一条，"来回改采纳对象"就能反复给同一个人刷 +15
     * （楼主与答主串通即可无限刷分）。
     */
    public static ReputationEvent acceptRevoked(Long authorId) {
        return new ReputationEvent(authorId, -15, "采纳被改判");
    }
}