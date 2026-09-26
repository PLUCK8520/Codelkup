package com.codelkup.module.user.controller;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.Result;
import com.codelkup.module.user.dto.LoginRequest;
import com.codelkup.module.user.dto.RegisterRequest;
import com.codelkup.module.user.dto.TokenResponse;
import com.codelkup.module.user.service.UserService;
import com.codelkup.security.CurrentUser;
import com.codelkup.security.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final RateLimitService rateLimitService;

    /**
     * 注册限流阈值（次 / 小时 / IP）。
     *
     * <p>做成可配而不是写死：默认 5 是"挡住批量枚举"的保守口径，但它同时会影响
     * 共享出口 IP（办公 / 校园 NAT）与"短时间重复跑验收脚本"的场景 ——
     * 部署方需要能一行配置调大，而不必改代码重新构建。
     */
    @Value("${codelkup.rate-limit.register-per-hour:5}")
    private int registerPerHour = 5;

    @Operation(summary = "注册", description = "注册成功后直接返回双 token，免去二次登录；按 IP 限流")
    @PostMapping("/register")
    public Result<TokenResponse> register(@Valid @RequestBody RegisterRequest request,
                                          HttpServletRequest httpRequest) {
        // ⚠️ 注册是"某邮箱是否已注册"的第二个出口：它必然要回答"这个邮箱能不能用"
        //    （EMAIL_ALREADY_EXISTS 与成功创建是可区分的两种结果）。check-email 已按 IP
        //    限流，若这里不限流，批量枚举与批量注册都能绕道这条路 —— 那道限流等于没做。
        //    用独立桶（不与 check-email 共用计数），避免正常注册被查重请求挤掉。
        if (!rateLimitService.tryAcquire("register", httpRequest.getRemoteAddr(), registerPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "注册过于频繁，请 1 小时后再试");
        }
        return Result.success(userService.register(request));
    }

    @Operation(summary = "登录", description = "邮箱 + 密码登录，返回 accessToken / refreshToken")
    @PostMapping("/login")
    public Result<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        // ⚠️ 这个 IP 必须是**不可伪造**的：登录失败锁定按「IP + 邮箱」判重。
        //    可信边界交给容器配置（application.yml: server.forward-headers-strategy=native
        //    + remoteip.internal-proxies）—— 只有直连对端是可信代理时才采信
        //    X-Forwarded-For，否则用真实 socket 地址。nginx 侧也用 $remote_addr
        //    覆盖而非追加该头（见 nginx.conf），客户端伪造的值进不来。
        //
        //    历史坑：此前这里手写解析 XFF 并取**第一段**，客户端自带
        //    `X-Forwarded-For: 1.2.3.4` 即可让每次尝试落在不同计数键上，
        //    5 次锁定永不触发 —— 防爆破形同不存在。
        return Result.success(userService.login(request, httpRequest.getRemoteAddr()));
    }

    @Operation(summary = "刷新凭证", description = "用 refreshToken 换一对新 token")
    @PostMapping("/refresh")
    public Result<TokenResponse> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body == null ? null : body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return Result.error(ErrorCode.PARAM_MISSING, "refreshToken 不能为空");
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

    @Operation(summary = "邮箱是否已被注册", description = "前端注册表单失焦时调用做异步提示；按 IP 限流")
    @GetMapping("/check-email")
    public Result<Map<String, Object>> checkEmail(
            @RequestParam @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") String email,
            HttpServletRequest httpRequest) {
        // ⚠️ 本接口按设计就要回答"某邮箱是否已注册"，等于一个可查询的枚举口子
        //    （详情页/正文里刻意避开了这个信息：登录失败对"邮箱不存在"与"密码错误"
        //    返回同一条错误，就是为了防枚举）。注册表单的即时提示是真实需求，
        //    不能直接砍掉，因此用**按 IP 的固定窗口限流**把批量枚举的成本抬到不可行
        //    （30 次 / 10 分钟 ≈ 单 IP 每天上限 4000 余次，扫不动有效规模的邮箱库）。
        if (!rateLimitService.tryAcquire("check_email", httpRequest.getRemoteAddr(), 30, 10)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "邮箱查重过于频繁，请 10 分钟后再试");
        }

        boolean taken = userService.isEmailTaken(email);
        // taken=true 时附带一条可直接展示的提示语，前端不必再判断
        return Result.success(taken
                ? Map.of("email", email, "taken", true, "message", "该邮箱已被注册")
                : Map.of("email", email, "taken", false, "message", "该邮箱可以使用"));
    }

    // ==================== 私有工具 ====================

    /** 从 "Bearer xxx" 中剥离前缀；格式不对返回 null（登出接口容错，不因此报错） */
    private String extractBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7).trim();
    }
}
