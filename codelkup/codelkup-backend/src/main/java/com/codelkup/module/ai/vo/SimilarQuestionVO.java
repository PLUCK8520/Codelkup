package com.codelkup.module.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 相似问题提示条目（M3-5）—— 精简版，只保留"能点进去看"所需的字段。
 *
 * <p>为什么不直接返回 {@code PostListVO}：提示面板只展示标题 + 一句命中片段 +
 * 板块，返回整份列表 VO（含计数、互动态、标签数组）既多传了用不到的数据，
 * 也把"提示"和"列表页"两个契约绑死 —— 将来列表 VO 加字段，提示接口不该跟着变。
 *
 * @author 刘
 */
@Data
@Schema(description = "相似问题提示条目")
public class SimilarQuestionVO {

    @Schema(description = "帖子ID（前端据此生成站内链接）", example = "20103")
    private Long postId;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "命中关键词附近的一段纯文本（后端已剥离 Markdown；前端负责高亮）")
    private String snippet;

    @Schema(description = "板块名（用于「在 后端开发 板块」这类提示）")
    private String boardName;
}