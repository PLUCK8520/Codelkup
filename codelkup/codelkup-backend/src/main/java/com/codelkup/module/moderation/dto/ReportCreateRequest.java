package com.codelkup.module.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交举报请求。
 *
 * @author 刘
 */
@Data
@Schema(description = "提交举报")
public class ReportCreateRequest {

    @NotNull(message = "举报对象类型不能为空")
    @Min(value = 1, message = "举报对象类型不合法")
    @Max(value = 2, message = "举报对象类型不合法")
    @Schema(description = "被举报对象类型：1帖子 2评论", example = "1")
    private Integer targetType;

    @NotNull(message = "举报对象ID不能为空")
    @Schema(description = "被举报对象ID")
    private Long targetId;

    /** 枚举值在 Service 校验（保持"存字符串"的扩展性：新增原因不需要改表结构） */
    @NotBlank(message = "请选择举报原因")
    @Schema(description = "原因：SPAM 广告 / ABUSE 辱骂骚扰 / ILLEGAL 违法违规 / OTHER 其他", example = "SPAM")
    private String reason;

    @Size(max = 200, message = "补充说明最多 200 字")
    @Schema(description = "补充说明（可选）")
    private String detail;
}