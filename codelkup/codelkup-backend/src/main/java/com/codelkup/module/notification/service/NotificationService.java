package com.codelkup.module.notification.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codelkup.common.result.PageResult;
import com.codelkup.module.notification.entity.Notification;
import com.codelkup.module.notification.event.NotificationEvent;
import com.codelkup.module.notification.mapper.NotificationMapper;
import com.codelkup.module.notification.vo.NotificationVO;
import com.codelkup.module.user.entity.User;
import com.codelkup.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 站内通知服务。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** 与 t_notification.content 的 VARCHAR(255) 对齐 */
    private static final int MAX_CONTENT_LENGTH = 255;

    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;

    // ==================== 写入 ====================

    /**
     * 落库一条通知（由 {@code NotificationEventListener} 异步调用）。
     *
     * <h2>「不给自己发通知」为什么放在这里</h2>
     * 这条规则对所有触发点都成立（自己评论自己的帖子、自己点赞自己的帖子……），
     * 如果要求每个调用方发布事件前先判断一次，那么新增一个触发点就可能漏掉，
     * 最终表现为"自己给自己刷了一屏通知"这种低级 bug。
     * 集中在这里判断，调用方只管"发生了这件事"，规则只有一处。
     *
     * <h2>去重：同类未读通知只保留一条</h2>
     * 覆盖"点赞 → 取消 → 点赞 → 取消 → 点赞"这类来回操作。
     * 没有去重时对方会收到三条一模一样的"XX 赞了你的帖子" ——
     * 这在通知中心里就是刷屏。只合并<b>未读</b>的：已读之后再来的同类通知
     * 对用户是有信息量的，不该被老记录吃掉（详见 Mapper 里的说明）。
     *
     * <p>去重判据是 {@code (receiver, sender, type, targetType, targetId)} 五元组，
     * 不含时间：同一人对同一帖子的多次点赞，在用户眼里本来就是"同一件事"。
     */
    public void create(NotificationEvent event) {
        if (event.receiverId() == null) {
            return;
        }
        if (event.receiverId().equals(event.senderId())) {
            log.debug("跳过自己触发自己的通知 | receiverId={} | type={}",
                    event.receiverId(), event.type());
            return;
        }

        // 合并：已有同源未读通知就不再插入，避免来回操作刷屏
        int similar = notificationMapper.countUnreadSimilar(
                event.receiverId(), event.senderId(), event.type(),
                event.targetType(), event.targetId());
        if (similar > 0) {
            log.debug("已存在同源未读通知，跳过 | receiverId={} | type={} | targetId={}",
                    event.receiverId(), event.type(), event.targetId());
            return;
        }

        String senderNickname = resolveNickname(event.senderId());

        Notification notification = new Notification();
        notification.setReceiverId(event.receiverId());
        notification.setSenderId(event.senderId());
        notification.setType(event.type());
        notification.setTargetType(event.targetType());
        notification.setTargetId(event.targetId());
        notification.setContent(truncate(buildContent(event.type(), senderNickname)));
        notification.setIsRead(0);

        notificationMapper.insert(notification);

        log.debug("通知已落库 | receiverId={} | type={} | targetId={}",
                event.receiverId(), event.type(), event.targetId());
    }

    // ==================== 读取 ====================

    /** 通知列表（分页） */
    public PageResult<NotificationVO> list(Long receiverId, boolean onlyUnread, int page, int size) {
        Page<NotificationVO> p = new Page<>(page, size);
        IPage<NotificationVO> result = notificationMapper.selectPage(p, receiverId, onlyUnread);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    /**
     * 未读数 —— 顶栏红点。
     *
     * <p>直接 COUNT(*) 而不再套一层 Redis 缓存：这个查询走
     * {@code idx_receiver_read(receiver_id, is_read)} 的等值前缀，
     * 千人级通知表上是毫秒级。为它引入缓存，就得同时引入"标记已读时如何失效"
     * 的一致性问题 —— 那是 S3 计数模型已经解决过一次的问题，没必要解决两次。
     */
    public int unreadCount(Long receiverId) {
        return notificationMapper.countUnread(receiverId);
    }

    // ==================== 已读 ====================

    /**
     * 标记单条已读。
     *
     * @return 是否真的改动了某一行（false 可能是通知不存在，也可能是别人的通知）
     */
    public boolean markRead(Long id, Long receiverId) {
        return notificationMapper.markRead(id, receiverId) > 0;
    }

    /** 全部标记已读，返回受影响行数 */
    public int markAllRead(Long receiverId) {
        return notificationMapper.markAllRead(receiverId);
    }

    // ==================== 私有工具 ====================

    /** 取触发者昵称；系统通知（senderId 为 null）显示"系统" */
    private String resolveNickname(Long senderId) {
        if (senderId == null) {
            return "系统";
        }
        User sender = userMapper.selectById(senderId);
        if (sender == null || sender.getNickname() == null || sender.getNickname().isBlank()) {
            // 账号已注销但仍留有历史通知 —— 不要因此让整条通知渲染成空白
            return "某位用户";
        }
        return sender.getNickname();
    }

    /**
     * 生成快照文案。
     *
     * <p>存的是<b>纯文本</b>而非渲染后的 HTML，理由与 D4 一致：
     * 展示层永不信任存储层的 HTML。前端把昵称与文案一起做转义即可。
     */
    private String buildContent(int type, String senderNickname) {
        return switch (type) {
            case Notification.TYPE_COMMENT -> senderNickname + " 评论了你的帖子";
            case Notification.TYPE_REPLY -> senderNickname + " 回复了你的评论";
            case Notification.TYPE_LIKE -> senderNickname + " 赞了你的帖子";
            case Notification.TYPE_FOLLOW -> senderNickname + " 关注了你";
            case Notification.TYPE_ACCEPT -> senderNickname + " 采纳了你的回答";
            case Notification.TYPE_SYSTEM -> "系统通知";
            default -> senderNickname + " 与你的内容产生了互动";
        };
    }

    /** 昵称最长 50 字，加上固定文案不会超 255，但截断是防御性写法 */
    private String truncate(String text) {
        if (text == null || text.length() <= MAX_CONTENT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_CONTENT_LENGTH);
    }
}
