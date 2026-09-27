package com.codelkup.module.moderation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 提交举报结果。
 *
 * <p>只回 {@code id + status}：举报人不需要看到队列内部结构，
 * 也不需要"被举报人是谁、有多少人举报过"这类聚合信息（那是审核端的视野）。
 *
 * @author 刘
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "举报提交结果")
public class ReportCreateVO {

    @Schema(description = "举报记录ID")
    private Long id;

    @Schema(description = "处理状态：0待处理", example = "0")
    private Integer status;
}