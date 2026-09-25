package com.codelkup.module.content.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 标签视图。
 *
 * @author 刘
 */
@Data
@Schema(description = "标签")
public class TagVO {

    @Schema(description = "标签ID")
    private Long id;

    @Schema(description = "标签名")
    private String name;

    @Schema(description = "被引用次数")
    private Integer useCount;
}
