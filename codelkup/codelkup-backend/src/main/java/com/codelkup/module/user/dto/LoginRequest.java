package com.codelkup.module.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求。
 *
 * <p>邮箱即登录凭证，无独立 username 字段（对应 DEV-DOC §7.3 登录方案决策）。
 *
 * @author 刘
 */
@Data
@Schema(description = "登录请求")
public class LoginRequest {

    @Schema(description = "邮箱", example = "user@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "邮箱不能为空")
    private String email;

    @Schema(description = "密码", example = "abc12345", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "密码不能为空")
    private String password;
}
