package com.codelkup.module.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 答案引用（M3-6）—— 一个片段对应一条站内讨论。
 *
 * <p>前端据 {@code postId} 生成站内链接（<b>纯文本渲染 + 结构化引用</b>，
 * 不走 v-html）：这样"引用可点"这件事不引入任何 XSS 面。
 *
 * @author 刘
 */
@Data
@Schema(description = "答案引用")
public class CitationVO {

    @Schema(description = "帖子ID（前端据此生成站内链接）", example = "20103")
    private Long postId;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "依据片段（后端已剥离 Markdown 的纯文本）")
    private String snippet;

    @Schema(description = "板块名")
    private String boardName;
}