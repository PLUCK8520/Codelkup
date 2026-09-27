package com.codelkup.module.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 草稿摘要（草稿箱列表用）—— 不含正文，只带一段纯文本预览。
 *
 * <p>列表刻意不带 {@code content}：一份草稿正文上限 5 万字，一页草稿箱
 * 没必要把几十份正文全传一遍；预览用后端生成的 {@code excerpt} 足够。
 *
 * @author 刘
 */
@Data
@Schema(description = "草稿摘要")
public class DraftSummaryVO {

    @Schema(description = "草稿归属：new / edit:<postId>", example = "new")
    private String scope;

    @Schema(description = "标题（可能为空字符串）")
    private String title;

    @Schema(description = "正文纯文本预览（已剥离 Markdown 语法，最多 120 字）")
    private String excerpt;

    @Schema(description = "所选板块（可能为 null）")
    private Long boardId;

    @Schema(description = "0普通帖 1提问帖")
    private Integer type;

    @Schema(description = "最后保存时间")
    private LocalDateTime updateTime;

    @Schema(description = "最后保存时间（epoch 毫秒）")
    private Long updateTimeEpoch;
}