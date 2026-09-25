package com.codelkup.module.user.controller;

import com.codelkup.common.result.Result;
import com.codelkup.module.user.dto.LoginRequest;
import com.codelkup.module.user.dto.RegisterRequest;
import com.codelkup.module.user.dto.TokenResponse;
import com.codelkup.module.user.service.UserService;
import com.codelkup.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口：注册 / 登录 / 刷新 / 登出 / 邮箱查重。
 *
 * <p>本组接口在 {@code SecurityConfig} 中整体放行（{@code /api/auth/**}），
 * 因为登录前本就不存在凭证。放行的是"接口可达"，不代表"不需要校验" ——
 * 每个方法内部仍有完整的参数校验与业务校验。
 *
 * @author 刘
 */
@Slf4j
@Tag(name = "认证", description = "注册 / 登录 / 刷新 / 登出")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final UserService userService;

    @Operation(summary = "注册", description = "注册成功后直接返回双 token，免去二次登录")
    @PostMapping("/register")
    public Result<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(userService.register(request));
    }

    @Operation(summary = "登录", description = "邮箱 + 密码登录，返回 accessToken / refreshToken")
    @PostMapping("/login")
    public Result<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        return Result.success(userService.login(request, resolveClientIp(httpRequest)));
    }

    @Operation(summary = "刷新凭证", description = "用 refreshToken 换一对新 token")
    @PostMapping("/refresh")
    public Result<TokenResponse> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body == null ? null : body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return Result.error(com.codelkup.common.result.ErrorCode.PARAM_MISSING, "refreshToken 不能为空");
        }
        return Result.success(userService.refresh(refreshToken));
    }

    @Operation(summary = "登出", description = "accessToken 拉黑 + refreshToken 白名单移除")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @CurrentUser Long userId) {
        userService.logout(extractBearer(authorization), userId);
        return Result.success();
    }

    @Operation(summary = "邮箱是否已被注册", description = "前端注册表单失焦时调用做异步提示")
    @GetMapping("/check-email")
    public Result<Map<String, Object>> checkEmail(
            @RequestParam @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") String email) {
        boolean taken = userService.isEmailTaken(email);
        // taken=true 时附带一条可直接展示的提示语，前端不必再判断
        return Result.success(taken
                ? Map.of("email", email, "taken", true, "message", "该邮箱已被注册")
                : Map.of("email", email, "taken", false, "message", "该邮箱可以使用"));
    }

    // ==================== 私有工具 ====================

    /**
     * 取客户端真实 IP。
     *
     * <p>登录失败计数是按 IP 维度统计的，若这里直接取 {@code getRemoteAddr()}
     * 在 Nginx / 负载均衡后面永远拿到的是代理 IP，限流会失效。
     * 生产部署时 {@code X-Forwarded-For} 由可信代理写入；本机开发时该头不存在，
     * 自然回落到 {@code getRemoteAddr()}。
     */
    private String resolveClientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP"};
        for (String header : headers) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                // X-Forwarded-For 可能是 "client, proxy1, proxy2"，取第一段
                int comma = value.indexOf(',');
                return (comma > 0 ? value.substring(0, comma) : value).trim();
            }
        }
        return request.getRemoteAddr();
    }

    /** 从 "Bearer xxx" 中剥离前缀；格式不对返回 null（登出接口容错，不因此报错） */
    private String extractBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7).trim();
    }
}
