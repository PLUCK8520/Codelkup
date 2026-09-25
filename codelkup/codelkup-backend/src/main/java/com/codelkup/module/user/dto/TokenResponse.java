package com.codelkup.module.user.dto;

import com.codelkup.module.user.vo.UserVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登录 / 注册成功后的凭证响应。
 *
 * @author 刘
 */
@Data
@Schema(description = "登录凭证")
public class TokenResponse {

    @Schema(description = "访问凭证，放入 Authorization: Bearer <token>")
    private String accessToken;

    @Schema(description = "刷新凭证，用于换取新的 accessToken")
    private String refreshToken;

    @Schema(description = "accessToken 有效期（秒）", example = "1800")
    private long expiresIn;

    @Schema(description = "凭证类型", example = "Bearer")
    private String tokenType = "Bearer";

    @Schema(description = "登录用户信息")
    private UserVO user;

    public TokenResponse(String accessToken, String refreshToken, long expiresIn, UserVO user) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresIn = expiresIn;
        this.user = user;
    }
}
