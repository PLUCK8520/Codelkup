package com.codelkup.module.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求。
 *
 * <p>为什么需要"原密码"：改密是账号接管的最后一道闸门 —— 只凭登录态就能改密码，
 * 那么任何一个被盗用的 accessToken（30 分钟窗口）都能把账号彻底锁成攻击者的。
 * 要求提供原密码，等于让攻击者必须同时拿到"会话"和"密码"两样东西。
 *
 * <p>新密码的强度规则与 {@link RegisterRequest} 保持一致（8~32 位、字母 + 数字）——
 * 两处刻意重复写注解而不是抽公共常量：一旦有人放宽注册强度，
 * 这里会是独立的第二处，review 时能立刻看出"两处规则不一致"。
 * 反过来，若抽成一个常量，改注册规则会静默地把改密规则一起改掉。
 *
 * @author 刘
 */
@Data
@Schema(description = "修改密码请求")
public class PasswordChangeRequest {

    @Schema(description = "原密码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入原密码")
    private String oldPassword;

    @Schema(description = "新密码（8~32 位，至少含字母和数字）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入新密码")
    @Size(min = 8, max = 32, message = "密码长度需为 8~32 位")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码需同时包含字母和数字")
    private String newPassword;
}