package com.codelkup.module.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.codelkup.module.notification.entity.Notification;
import com.codelkup.module.notification.vo.NotificationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 通知 Mapper。
 *
 * @author 刘
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * 通知列表（分页）。
     *
     * <p>{@code onlyUnread} 为 false 时走 {@code idx_receiver_time}，
     * 为 true 时走 {@code idx_receiver_read} —— 两个索引分别对应这两条查询路径，
     * 这样"全部"和"只看未读"都能命中索引排序，不需要 filesort。
     *
     * <p>{@code LEFT JOIN t_user}：系统通知的 {@code sender_id} 是 NULL。
     * 用 INNER JOIN 会把系统通知整个过滤掉，所以必须 LEFT。
     */
    @Select("""
            <script>
            SELECT n.id, n.type, n.sender_id, n.target_type, n.target_id,
                   n.content, n.is_read, n.create_time,
                   u.nickname AS sender_nickname, u.avatar AS sender_avatar
            FROM t_notification n
            LEFT JOIN t_user u ON u.id = n.sender_id
            WHERE n.receiver_id = #{receiverId}
            <if test="onlyUnread"> AND n.is_read = 0 </if>
            ORDER BY n.create_time DESC, n.id DESC
            </script>
            """)
    IPage<NotificationVO> selectPage(IPage<NotificationVO> page,
                                     @Param("receiverId") Long receiverId,
                                     @Param("onlyUnread") boolean onlyUnread);

    /** 未读数 —— 顶栏角标，高频调用，走 idx_receiver_read 的等值前缀 */
    @Select("SELECT COUNT(*) FROM t_notification WHERE receiver_id = #{receiverId} AND is_read = 0")
    int countUnread(@Param("receiverId") Long receiverId);

    /** 全部标记已读 */
    @Update("UPDATE t_notification SET is_read = 1 WHERE receiver_id = #{receiverId} AND is_read = 0")
    int markAllRead(@Param("receiverId") Long receiverId);

    /**
     * 标记单条已读。
     *
     * <p>⚠️ {@code receiver_id} 必须出现在 WHERE 里，<b>不能只按 id 更新</b>：
     * 否则任何登录用户只要遍历通知 ID，就能把别人的未读状态改掉（越权写）。
     * 带上 receiver_id 后，即使猜到别人的通知 ID，UPDATE 也匹配不到任何行
     * （受影响行数为 0），天然免疫这类水平越权。
     */
    @Update("UPDATE t_notification SET is_read = 1 WHERE id = #{id} AND receiver_id = #{receiverId}")
    int markRead(@Param("id") Long id, @Param("receiverId") Long receiverId);

    /**
     * 是否已存在"同源且未读"的通知 —— 用于合并、防止刷屏。
     *
     * <h2>为什么需要它</h2>
     * 没有这个方法时，这样的操作序列会让接收者的通知列表刷屏：
     * <pre>
     *   点赞 → 取消 → 点赞 → 取消 → 点赞
     *   → 对方收到 3 条"XX 赞了你的帖子"
     * </pre>
     * 用户看到的是三条一模一样的通知，体验很差。判重后同类未读通知只保留一条。
     *
     * <h2>为什么只合并【未读】的</h2>
     * 已读意味着用户已经消费过这条信息，此时再来一条新的同类通知
     * 反而是有价值的（"刚才那个人又赞了我"）。只合并未读，兼顾了"不刷屏"
     * 与"不丢事件"两个目标。
     *
     * <h2>为什么不用唯一索引（对比点赞防重的做法）</h2>
     * 点赞防重可以靠唯一索引，是因为"一人对一目标只能有一条记录"是<b>硬约束</b>。
     * 而通知的合并规则依赖 {@code is_read} 这个<b>可变状态</b> ——
     * 用户读完通知后 is_read 变成 1，此时应该允许插入新的同类通知。
     * 若用唯一索引，已读的老通知会永久挡住新通知的插入。
     * 所以这里的判重只能是一次显式查询，这是语义决定的，不是设计偷懒。
     *
     * <p>⚠️ {@code sender_id} / {@code target_id} 为 null 时用 {@code IS NULL}：
     * SQL 里 {@code = NULL} 永远不成立，写错会导致系统通知（sender 为 null）
     * 的判重完全失效、每条都插入。这是个静默出错的坑。
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM t_notification
            WHERE receiver_id = #{receiverId}
              AND is_read = 0
              AND type = #{type}
              <choose>
                  <when test="senderId != null">AND sender_id = #{senderId}</when>
                  <otherwise>AND sender_id IS NULL</otherwise>
              </choose>
              <if test="targetType != null">AND target_type = #{targetType}</if>
              <choose>
                  <when test="targetId != null">AND target_id = #{targetId}</when>
                  <otherwise>AND target_id IS NULL</otherwise>
              </choose>
            </script>
            """)
    int countUnreadSimilar(@Param("receiverId") Long receiverId,
                           @Param("senderId") Long senderId,
                           @Param("type") int type,
                           @Param("targetType") Integer targetType,
                           @Param("targetId") Long targetId);
}
