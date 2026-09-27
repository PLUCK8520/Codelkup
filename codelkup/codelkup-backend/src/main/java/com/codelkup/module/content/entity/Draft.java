package com.codelkup.module.content.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 服务端草稿实体，对应表 {@code t_draft}（V9 / M4-15）。
 *
 * <p>与 {@link Post} 的关键差别：<b>没有 status / 计数 / 逻辑删除</b>。
 * 草稿是"还没发布的内容"，不参与列表、检索、计数，也没有"已删除的草稿"这种状态 ——
 * 用户点删除就是真删（物理删除），不留审计痕迹。
 *
 * <p>{@code tags} 存 JSON 数组字符串（如 {@code ["Java","并发"]}），
 * 不是关联表：草稿不该污染 {@code t_tag} 的 {@code use_count} 反范式计数。
 *
 * @author 刘
 */
@Data
@TableName("t_draft")
public class Draft implements Serializable {

    /** 新建稿的 scope —— 与前端 useDraft 的 scope 命名严格一致 */
    public static final String SCOPE_NEW = "new";

    /** 编辑稿 scope 前缀：edit:<postId> */
    public static final String SCOPE_EDIT_PREFIX = "edit:";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** new / edit:<postId> */
    private String scope;

    private String title;

    /** 正文 —— Markdown 原文（与 t_post.content 同口径，绝不存渲染后 HTML） */
    private String content;

    private Long boardId;

    /** 0普通帖 1提问帖 */
    private Integer type;

    /** 标签名 JSON 数组字符串 */
    private String tags;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}