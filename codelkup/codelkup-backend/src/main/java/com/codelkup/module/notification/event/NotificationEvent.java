package com.codelkup.module.notification.event;

import com.codelkup.module.notification.entity.Notification;

/**
 * 通知事件。
 *
 * <h2>为什么用事件，而不是让业务代码直接调用 NotificationService</h2>
 * 有三个理由，按重要性排序：
 * <ol>
 *   <li><b>不阻塞主流程</b> —— 发通知失败不该让"关注成功"变成失败。
 *       落库交给异步监听器，主事务只负责业务本身。</li>
 *   <li><b>避免模块间硬依赖</b> —— 「关注」属于 social、「评论」属于 interaction，
 *       它们不该各自持有 NotificationService 的引用。发一个事件，
 *       由通知模块自己决定怎么处理，依赖方向变成"业务 → 事件 ← 通知模块"。</li>
 *   <li><b>触发点集中可审计</b> —— 想知道"什么情况下会发通知"，
 *       看 {@code publishEvent} 的调用点即可，不必翻遍各 Service。</li>
 * </ol>
 *
 * <p>与 S3 的 {@code CountChangedEvent} 是同一套机制，连
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 的理由都一致：
 * 主事务回滚时事件必须被丢弃，否则会留下指向不存在数据的通知。
 *
 * <h2>为什么事件里不带文案</h2>
 * 文案需要发送者昵称，而调用方（比如"点赞"）手头只有 ID。让调用方为了发个通知
 * 去查一次用户表，是把通知的关注点泄漏到了业务代码里。所以事件只描述<b>事实</b>
 * （谁、对谁、什么类型、目标是谁），文案由监听器在异步线程里查库生成 ——
 * 顺带还带来一个好处：改文案只需要改一处。
 *
 * @param receiverId 接收者（通知要发给谁）
 * @param senderId   触发者（可为 null，表示系统）
 * @param type       {@link Notification} 里的 TYPE_* 常量
 * @param targetType {@link Notification} 里的 TARGET_* 常量，可为 null
 * @param targetId   目标 ID，可为 null
 * @param extra      附加文案（如被采纳/回复时的目标标题），可为 null
 * @author 刘
 */
public record NotificationEvent(
        Long receiverId,
        Long senderId,
        int type,
        Integer targetType,
        Long targetId,
        String extra
) {

    /** 关注了你 */
    public static NotificationEvent follow(Long receiverId, Long senderId) {
        return new NotificationEvent(receiverId, senderId, Notification.TYPE_FOLLOW, null, null, null);
    }

    /** 评论了你的帖子 */
    public static NotificationEvent comment(Long receiverId, Long senderId, Long postId) {
        return new NotificationEvent(receiverId, senderId, Notification.TYPE_COMMENT,
                Notification.TARGET_POST, postId, null);
    }

    /**
     * 回复了你的评论。
     *
     * <p>目标指向<b>帖子</b>而非那条评论：评论没有独立详情页，
     * 通知点进去应当看到完整讨论上下文。若把 targetId 设为 commentId，
     * 前端还得再查一次"这条评论属于哪个帖子"才能跳转 —— 白绕一圈。
     */
    public static NotificationEvent reply(Long receiverId, Long senderId, Long postId) {
        return new NotificationEvent(receiverId, senderId, Notification.TYPE_REPLY,
                Notification.TARGET_POST, postId, null);
    }

    /** 点赞了你的帖子 */
    public static NotificationEvent likePost(Long receiverId, Long senderId, Long postId) {
        return new NotificationEvent(receiverId, senderId, Notification.TYPE_LIKE,
                Notification.TARGET_POST, postId, null);
    }

    /**
     * 点赞了你的评论。
     *
     * <p>⚠️ 目标是<b>评论</b>而不是帖子（第三轮审计修正）：此前与 {@link #likePost}
     * 发出了完全相同的五元组（type=3 + targetType=帖子 + targetId=帖子），
     * 后果有二 —— ① 去重（同源未读只留一条）把"先赞帖子、再赞该帖评论"的第二条
     * 当成重复吃掉；② 文案一律是"赞了你的帖子"，对评论点赞是错的。
     * 跳转不受影响：通知列表由服务端 JOIN 出 {@code targetPostId}，前端仍跳到帖子。
     */
    public static NotificationEvent likeComment(Long receiverId, Long senderId, Long commentId) {
        return new NotificationEvent(receiverId, senderId, Notification.TYPE_LIKE,
                Notification.TARGET_COMMENT, commentId, null);
    }

    /** 采纳了你的回答 */
    public static NotificationEvent accept(Long receiverId, Long senderId, Long postId) {
        return new NotificationEvent(receiverId, senderId, Notification.TYPE_ACCEPT,
                Notification.TARGET_POST, postId, null);
    }
}
