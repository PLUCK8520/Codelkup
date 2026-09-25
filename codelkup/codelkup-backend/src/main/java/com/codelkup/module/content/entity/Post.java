package com.codelkup.module.content.entity;

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
 * 帖子实体，对应表 {@code t_post}。
 *
 * <p>⚠️ <b>修订 D4</b>：本实体刻意<b>没有 {@code contentHtml} 字段</b>。
 * 原设计在写入时渲染并存 HTML，已改为读时渲染 + Redis 缓存，理由：
 * <ol>
 *   <li>存储层保存未经消毒的 HTML → XSS 风险被固化进数据库，事后无法补救</li>
 *   <li>Markdown 渲染器升级后，存量 HTML 与新渲染结果不一致，无法批量修正</li>
 *   <li>读时渲染 + 缓存同样避免重复渲染，收益相同而风险更低</li>
 * </ol>
 *
 * @author 刘
 */
@Data
@TableName("t_post")
public class Post implements Serializable {

    /** 帖子状态：待审核 */
    public static final int STATUS_PENDING = 0;
    /** 帖子状态：已发布 */
    public static final int STATUS_PUBLISHED = 1;
    /** 帖子状态：已删除（作者自己删） */
    public static final int STATUS_DELETED = 2;
    /** 帖子状态：已封禁（管理员删） */
    public static final int STATUS_BANNED = 3;

    /** 类型：普通帖 */
    public static final int TYPE_NORMAL = 0;
    /** 类型：提问帖 */
    public static final int TYPE_QUESTION = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    /** 正文 —— Markdown 原文。绝不在此字段存渲染后的 HTML */
    private String content;

    /** 摘要（列表页用），发帖时从正文剥离 Markdown 语法后截断生成 */
    private String summary;

    private Long authorId;

    private Long boardId;

    /** 0普通帖 1提问帖 */
    private Integer type;

    /** 0待审 1已发布 2已删 3已封禁 */
    private Integer status;

    /** 是否置顶：0否 1是 */
    private Integer isTop;

    /** 是否加精：0否 1是 */
    private Integer isFeatured;

    /** 采纳的评论ID（提问帖用） */
    private Long acceptedCommentId;

    private Integer viewCount;

    private Integer likeCount;

    private Integer commentCount;

    private Integer collectCount;

    /** 热度分，由定时任务计算写入 */
    private Double score;

    private LocalDateTime lastActive;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
