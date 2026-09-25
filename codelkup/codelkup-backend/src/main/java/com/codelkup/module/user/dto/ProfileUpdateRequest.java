package com.codelkup.module.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改个人资料请求。
 *
 * <p>字段全部可选，只更新传入的部分。
 *
 * <p>⚠️ <b>刻意不含 email 与 password</b>：
 * <ul>
 *   <li>改邮箱要走"新邮箱验证"流程（否则可被用来接管账号），不属于本接口</li>
 *   <li>改密码要走"验证旧密码"流程，同上</li>
 * </ul>
 * 把这两个高风险字段排除在"资料编辑"之外，是安全设计的常见做法。
 *
 * @author 刘
 */
@Data
@Schema(description = "修改个人资料（字段可选）")
public class ProfileUpdateRequest {

    @Schema(description = "昵称", example = "阿布")
    @Size(min = 2, max = 20, message = "昵称长度需为 2~20 位")
    @Pattern(regexp = ".*\\S.*", message = "昵称不能全是空格")
    private String nickname;

    @Schema(description = "个人简介", example = "27 届，方向 AI 应用开发")
    @Size(max = 255, message = "个人简介不能超过 255 个字符")
    private String bio;

    @Schema(description = "技术标签，逗号分隔", example = "Java,Spring Boot,LLM")
    @Size(max = 255, message = "技术标签不能超过 255 个字符")
    private String techTags;
}
