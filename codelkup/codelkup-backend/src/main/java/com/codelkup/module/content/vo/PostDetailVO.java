package com.codelkup.module.content.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子详情。
 *
 * <p>比 {@link PostListVO} 多出 {@code content}（Markdown 原文）与 {@code author} 完整信息。
 *
 * <p>⚠️ 返回的是 <b>Markdown 原文</b>，不是渲染后的 HTML —— 前端负责渲染并做 XSS 消毒
 * （DOMPurify）。详见 DEV-DOC §13.4 的 XSS 硬性要求与修订 D4。
 *
 * @author 刘
 */
@Data
@Schema(description = "帖子详情")
public class PostDetailVO {

    @Schema(description = "帖子ID")
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "正文（Markdown 原文，前端渲染 + 消毒）")
    private String content;

    @Schema(description = "摘要")
    private String summary;

    @Schema(description = "作者ID")
    private Long authorId;

    @Schema(description = "板块ID")
    private Long boardId;

    @Schema(description = "0普通帖 1提问帖")
    private Integer type;

    @Schema(description = "0待审 1已发布 2已删 3已封禁")
    private Integer status;

    @Schema(description = "是否置顶")
    private Integer isTop;

    @Schema(description = "是否加精")
    private Integer isFeatured;

    @Schema(description = "采纳的评论ID")
    private Long acceptedCommentId;

    @Schema(description = "浏览数")
    private Integer viewCount;

    @Schema(description = "点赞数")
    private Integer likeCount;

    @Schema(description = "评论数")
    private Integer commentCount;

    @Schema(description = "收藏数")
    private Integer collectCount;

    @Schema(description = "最后活跃时间")
    private LocalDateTime lastActive;

    @Schema(description = "发布时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    // ---- JOIN 字段 ----

    @Schema(description = "作者昵称")
    private String authorNickname;

    @Schema(description = "作者头像")
    private String authorAvatar;

    @Schema(description = "板块名")
    private String boardName;

    @Schema(description = "板块 URL 短名")
    private String boardSlug;

    // ---- 组装字段（Service 层填充，非 SQL 直接映射）----

    @Schema(description = "作者简要信息")
    private UserBriefVO author;

    @Schema(description = "标签列表")
    private List<TagVO> tags;

    @Schema(description = "当前登录用户是否可编辑此帖（作者本人或管理员）")
    private Boolean canEdit;

    // ---- 互动状态（Sprint 3：需要登录态才能算，游客恒为 null）----
    //
    // ⚠️ @JsonInclude(ALWAYS) 是必须的，不能省：
    //    全局配置 default-property-inclusion: non_null 会把 null 字段整个删掉，
    //    导致游客拿到的响应里**没有 liked 这个键**，与「未登录为 null」的契约不符。
    //    前端无法区分「字段不存在」和「值为 null」——前者会让
    //    `liked === null` 判断失效。这里刻意把这两个字段排除在全局策略之外，
    //    让它们始终出现：null 明确表达「不知道（未登录）」，false 表达「没点过」。

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "当前登录用户是否已点赞（未登录为 null）")
    private Boolean liked;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "当前登录用户是否已收藏（未登录为 null）")
    private Boolean collected;
}
