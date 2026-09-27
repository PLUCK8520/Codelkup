package com.codelkup.module.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 处理举报请求（管理员）。
 *
 * @author 刘
 */
@Data
@Schema(description = "处理举报")
public class ReportHandleRequest {

    /** 取值在 Service 校验：非法值要给出"只能是 dismiss 或 delete_target"的可读提示 */
    @NotBlank(message = "处理动作不能为空")
    @Schema(description = "处理动作：dismiss 驳回 / delete_target 删除内容", example = "dismiss")
    private String action;

    @Size(max = 200, message = "处理备注最多 200 字")
    @Schema(description = "处理备注（可选，会随举报记录留痕）")
    private String note;
}