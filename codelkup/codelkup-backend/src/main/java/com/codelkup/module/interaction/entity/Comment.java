package com.codelkup.module.interaction.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评论实体，对应表 {@code t_comment}。
 *
 * <p><b>只做二级评论</b>：{@code parentId == 0} 为一级评论，否则是该评论的回复。
 * 查库时一次取回某帖全部评论，在应用层按 {@code parentId} 分组拼成树 ——
 * 没有递归查询，也没有无限嵌套。
 *
 * <p>注意区分 {@code parentId} 与 {@code replyUserId}：
 * <ul>
 *   <li>{@code parentId} —— 结构信息，决定挂在哪条根评论下（永远是<b>一级评论</b>的 ID）</li>
 *   <li>{@code replyUserId} —— 展示信息，用于渲染「回复 @张三」</li>
 * </ul>
 * 举例：A 发了一级评论，B 回复 A，C 回复 B。
 * 则 C 的 {@code parentId} 是 A 的 ID（不是 B 的），{@code replyUserId} 是 B。
 * 这样树只有两层，但"回复谁"的信息不丢。
 *
 * @author 刘
 */
@Data
@TableName("t_comment")
public class Comment implements Serializable {

    /** 一级评论的 parentId 约定值 */
    public static final long ROOT_PARENT_ID = 0L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;

    private Long authorId;

    /** 纯文本，不渲染 Markdown —— 评论是即时讨论，不需要复杂排版，也少一层 XSS 面 */
    private String content;

    /** 0=一级评论；非0=所挂的根评论 ID */
    private Long parentId;

    /** 被回复者 ID（渲染「回复 @某人」用），可为空 */
    private Long replyUserId;

    /** 点赞数（MySQL 侧基线，实时值以 Redis 为准） */
    private Integer likeCount;

    /** 是否被楼主采纳：0否 1是，仅提问帖有意义 */
    private Integer isAccepted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
