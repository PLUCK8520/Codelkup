package com.codelkup.module.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 帖子搜索参数。
 *
 * @author 刘
 */
@Data
@Schema(description = "帖子搜索参数")
public class PostSearchRequest {

    /**
     * 关键词。
     *
     * <p>长度上限 50 与 {@code SearchService.MAX_QUERY_LENGTH} 保持一致 ——
     * DTO 校验负责给出<b>友好提示</b>（40001 + "关键词过长"），
     * Service 内的截断负责<b>兜底</b>（万一有别的调用方绕过校验）。
     * 两处都要有，职责不同。
     */
    @NotBlank(message = "搜索关键词不能为空")
    @Size(max = 50, message = "关键词最长 50 个字符")
    @Schema(description = "搜索关键词（标题 + 正文）", example = "虚拟线程", requiredMode = Schema.RequiredMode.REQUIRED)
    private String keyword;

    @Schema(description = "限定板块ID（可选）")
    private Long boardId;

    @Schema(description = "限定标签ID（可选）")
    private Long tagId;

    @Schema(description = "限定作者ID（可选）")
    private Long authorId;

    @Schema(description = "排序：relevance=相关性 | latest=最新 | hot=热度", example = "relevance",
            allowableValues = {"relevance", "latest", "hot"})
    private String sort = "relevance";

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码最小为 1")
    private Integer page = 1;

    @Schema(description = "每页条数（1~50）", example = "20")
    @Min(value = 1, message = "每页最少 1 条")
    @Max(value = 50, message = "每页最多 50 条")
    private Integer size = 20;
}
