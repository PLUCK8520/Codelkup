package com.codelkup.module.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.codelkup.module.notification.entity.Notification;
import com.codelkup.module.notification.vo.NotificationTypeUnreadVO;
import com.codelkup.module.notification.vo.NotificationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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
     *
     * <p>★ V0.4 新增 {@code types}：通知中心的类型分组（tab）筛选。
     * <b>用 IN 而不是 {@code type = #{type}}</b>：前端的「评论与回复」tab 同时包含
     * 1（评论）与 2（回复）—— 对用户来说两者都是"有人回了我"，拆成两个 tab 反而难找。
     * 参数为 null 或空集合时整个条件不拼，等同"全部类型"。
     *
     * <p>★ 第三轮审计新增 {@code target_post_id}：{@code target_type = 2}（评论点赞）时
     * 由 {@code LEFT JOIN t_comment} 解析出评论所属帖子 —— 前端据此仍跳转到帖子看上下文，
     * 不必再为"这条评论属于哪个帖子"多发一次请求（与举报模块的 {@code ReportAdminVO.targetPostId} 同一手法）。
     * 评论已被删时该列为 NULL，前端据此显示为不可点击，符合"删了就别再引路"的口径。
     */
    @Select("""
            <script>
            SELECT n.id, n.type, n.sender_id, n.target_type, n.target_id,
                   n.content, n.is_read, n.create_time,
                   u.nickname AS sender_nickname, u.avatar AS sender_avatar,
                   CASE WHEN n.target_type = 2 THEN c.post_id ELSE n.target_id END AS target_post_id
            FROM t_notification n
            LEFT JOIN t_user u ON u.id = n.sender_id
            LEFT JOIN t_comment c ON n.target_type = 2 AND c.id = n.target_id
            WHERE n.receiver_id = #{receiverId}
            <if test="onlyUnread"> AND n.is_read = 0 </if>
            <if test="types != null and types.size() > 0">
                AND n.type IN
                <foreach collection="types" item="t" open="(" separator="," close=")">#{t}</foreach>
            </if>
            ORDER BY n.create_time DESC, n.id DESC
            </script>
            """)
    IPage<NotificationVO> selectPage(IPage<NotificationVO> page,
                                     @Param("receiverId") Long receiverId,
                                     @Param("onlyUnread") boolean onlyUnread,
                                     @Param("types") List<Integer> types);

    /** 未读数 —— 顶栏角标，高频调用，走 idx_receiver_read 的等值前缀 */
    @Select("SELECT COUNT(*) FROM t_notification WHERE receiver_id = #{receiverId} AND is_read = 0")
    int countUnread(@Param("receiverId") Long receiverId);

    /**
     * 各类型的未读数 —— 通知中心的分组角标。
     *
     * <p>一条 {@code GROUP BY} 顶掉"每个 tab 各发一次 COUNT"。
     * 走 {@code idx_receiver_read} 的等值前缀（receiver_id + is_read 都在索引里）
     * 回表取 type，千人级通知表上是毫秒级。
     *
     * <p>⚠️ 必须用 {@code @Results} 显式把 {@code cnt} 映射到 {@code count}：
     * 全局的驼峰映射只处理"下划线 → 驼峰"，而列别名 {@code cnt} 与属性名
     * {@code count} 之间没有这种关系 —— 不写这段会得到一个 count 恒为 null 的结果，
     * 而且是静默的（前端只会看到角标不显示）。
     *
     * <p>不返回"未读为 0 的类型"：让数据库决定哪些类型有数据，
     * 调用方按 0 处理即可，也免得类型新增时要同步维护一份清单。
     */
    @Select("""
            SELECT type, COUNT(*) AS cnt
            FROM t_notification
            WHERE receiver_id = #{receiverId} AND is_read = 0
            GROUP BY type
            """)
    @Results({
            @Result(column = "type", property = "type"),
            @Result(column = "cnt", property = "count")
    })
    List<NotificationTypeUnreadVO> countUnreadByType(@Param("receiverId") Long receiverId);

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
