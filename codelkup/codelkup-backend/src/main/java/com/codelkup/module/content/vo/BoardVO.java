package com.codelkup.module.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 板块视图（含板块下的帖子数）。
 *
 * @author 刘
 */
@Data
@Schema(description = "板块")
public class BoardVO {

    @Schema(description = "板块ID")
    private Long id;

    @Schema(description = "板块名")
    private String name;

    @Schema(description = "URL 短名")
    private String slug;

    @Schema(description = "板块描述")
    private String description;

    @Schema(description = "图标标识")
    private String icon;

    @Schema(description = "排序值，越小越靠前")
    private Integer sortOrder;

    @Schema(description = "帖子数")
    private Integer postCount;
}
