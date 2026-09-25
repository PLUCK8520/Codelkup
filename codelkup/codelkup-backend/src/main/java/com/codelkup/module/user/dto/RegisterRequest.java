package com.codelkup.module.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。
 *
 * <p>校验规则对应 DEV-DOC §11.2：
 * <ul>
 *   <li>email —— 必填、格式、长度 ≤ 100</li>
 *   <li>password —— 必填、8~32、至少含字母和数字</li>
 *   <li>nickname —— 必填、2~20、不能全是空白</li>
 * </ul>
 *
 * @author 刘
 */
@Data
@Schema(description = "注册请求")
public class RegisterRequest {

    @Schema(description = "邮箱（登录凭证）", example = "user@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过 100 个字符")
    private String email;

    @Schema(description = "密码（8~32 位，至少含字母和数字）", example = "abc12345", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度需为 8~32 位")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码需同时包含字母和数字")
    private String password;

    @Schema(description = "昵称（可重复，2~20 位）", example = "阿布", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "昵称不能为空")
    @Size(min = 2, max = 20, message = "昵称长度需为 2~20 位")
    @Pattern(regexp = ".*\\S.*", message = "昵称不能全是空格")
    private String nickname;
}
