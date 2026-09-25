package com.codelkup.module.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 帖子列表查询参数。
 *
 * <p>用对象接收而非一长串 {@code @RequestParam}：参数多时签名会失控，
 * 且 SpringDoc 能自动生成完整的查询参数文档。
 *
 * @author 刘
 */
@Data
@Schema(description = "帖子列表查询参数")
public class PostQueryRequest {

    @Schema(description = "板块ID（可选）")
    private Long boardId;

    @Schema(description = "作者ID（可选）")
    private Long authorId;

    @Schema(description = "标签ID（可选）")
    private Long tagId;

    @Schema(description = "帖子类型：0普通帖 1提问帖（可选）")
    private Integer type;

    @Schema(description = "排序：latest=最新 | hot=热度 | active=最后活跃", example = "latest",
            allowableValues = {"latest", "hot", "active"})
    private String sort = "latest";

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码最小为 1")
    private Integer page = 1;

    @Schema(description = "每页条数（1~100）", example = "20")
    @Min(value = 1, message = "每页最少 1 条")
    @Max(value = 100, message = "每页最多 100 条")
    private Integer size = 20;
}
