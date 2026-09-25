package com.codelkup.module.interaction.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户互动记录（点赞 / 收藏），对应表 {@code t_user_action}。
 *
 * <p><b>这张表存在的两个理由</b>：
 * <ol>
 *   <li><b>防重</b> —— {@code uk_user_target_action} 唯一索引让"重复点赞"在
 *       数据库层面不可能发生，业务代码不需要"先查再写"（那样有并发竞态窗口）。</li>
 *   <li><b>可重建</b> —— Redis 计数器丢失时，{@code SELECT COUNT(*)}
 *       即可恢复真值。这是"Redis 挂了怎么办"的答案。</li>
 * </ol>
 *
 * <p>把点赞和收藏放进一张表，是因为它们的结构完全同构（谁 + 对什么 + 做了什么），
 * 拆两张表只会让索引和代码都翻倍。区别仅由 {@code actionType} 表达。
 *
 * @author 刘
 */
@Data
@TableName("t_user_action")
public class UserAction implements Serializable {

    /** 目标类型：帖子 */
    public static final int TARGET_POST = 1;
    /** 目标类型：评论 */
    public static final int TARGET_COMMENT = 2;

    /** 动作类型：点赞 */
    public static final int ACTION_LIKE = 1;
    /** 动作类型：收藏 */
    public static final int ACTION_COLLECT = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 1帖子 2评论 */
    private Integer targetType;

    private Long targetId;

    /** 1点赞 2收藏 */
    private Integer actionType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 便捷构造 */
    public static UserAction of(Long userId, int targetType, Long targetId, int actionType) {
        UserAction action = new UserAction();
        action.setUserId(userId);
        action.setTargetType(targetType);
        action.setTargetId(targetId);
        action.setActionType(actionType);
        return action;
    }
}
