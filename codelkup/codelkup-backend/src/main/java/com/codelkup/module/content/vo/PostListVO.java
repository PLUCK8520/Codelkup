package com.codelkup.module.content.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子列表项。
 *
 * <p>⚠️ <b>刻意不含 {@code content} 正文</b>：列表页只展示摘要，
 * 一页 20 条若带上 MEDIUMTEXT 正文，响应体积会膨胀两个数量级。
 *
 * @author 刘
 */
@Data
@Schema(description = "帖子列表项")
public class PostListVO {

    @Schema(description = "帖子ID")
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "摘要")
    private String summary;

    @Schema(description = "作者ID")
    private Long authorId;

    @Schema(description = "板块ID")
    private Long boardId;

    @Schema(description = "0普通帖 1提问帖")
    private Integer type;

    @Schema(description = "是否置顶")
    private Integer isTop;

    @Schema(description = "是否加精")
    private Integer isFeatured;

    @Schema(description = "浏览数")
    private Integer viewCount;

    @Schema(description = "点赞数")
    private Integer likeCount;

    @Schema(description = "评论数")
    private Integer commentCount;

    @Schema(description = "收藏数")
    private Integer collectCount;

    @Schema(description = "热度分")
    private Double score;

    @Schema(description = "最后活跃时间")
    private LocalDateTime lastActive;

    @Schema(description = "发布时间")
    private LocalDateTime createTime;

    // ---- JOIN 出来的冗余展示字段 ----

    @Schema(description = "作者昵称")
    private String authorNickname;

    @Schema(description = "作者头像")
    private String authorAvatar;

    @Schema(description = "板块名")
    private String boardName;

    @Schema(description = "板块 URL 短名")
    private String boardSlug;

    @Schema(description = "标签列表")
    private List<TagVO> tags;

    /**
     * 检索命中片段（Sprint 4，<b>仅搜索接口填充</b>）。
     *
     * <p>普通列表页为 null（全局 non_null 策略下该键不出现），
     * 搜索时为"关键词附近的一段纯文本"，用于让用户看出这条为什么被搜出来 ——
     * 因为 {@code summary} 取自正文开头，关键词在正文深处时它是高亮不出来的。
     *
     * <p>⚠️ 内容是<b>纯文本</b>，不含任何高亮标记。标红是前端职责
     * （后端返回数据、前端负责表现；也让后端不必关心 HTML 转义）。
     */
    @Schema(description = "检索命中片段（纯文本，仅搜索接口返回）")
    private String snippet;

    // ---- 互动状态（Sprint 3）----
    //
    // ⚠️ @JsonInclude(ALWAYS) 不能省：全局 non_null 策略会删掉 null 字段，
    //    游客看到的列表项里就没有 liked 键了，与「未登录为 null」的契约不符。
    //    详情 VO 里有同样处理，原因一致。

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "当前登录用户是否已点赞（未登录为 null）")
    private Boolean liked;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "当前登录用户是否已收藏（未登录为 null）")
    private Boolean collected;
}
