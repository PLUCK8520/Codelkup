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
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
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

    /** 注册开关（PRE-MORTEM T0-2）：生产/演示站默认仍为 true，由 .env 显式置 false */
    @Value("${codelkup.auth.registration-enabled:true}")
    private boolean registrationEnabled = true;

    /** 刷新凭证限流（L-4 补齐）：默认 120 次/小时/IP —— 未认证接口按 IP 反滥用 */
    @Value("${codelkup.rate-limit.refresh-per-hour:120}")
    private int refreshPerHour = 120;

    /**
     * Cookie 的 Secure 标志（2026-09-29 第五轮审计 #3）。
     * 生产（https）置 true；本地 dev 走 http，置 true 会让浏览器直接不存 Cookie。
     */
    @Value("${codelkup.security.cookie-secure:false}")
    private boolean cookieSecure;

    /**
     * refresh token 的 HttpOnly Cookie（第五轮审计 #3：refresh 不再依赖 localStorage）。
     *
     * <p>为什么是<b>双轨</b>而不是"彻底不返回 body"：
     * <ul>
     *   <li>响应体**仍返回** refreshToken、刷新/登出接口**仍接受** body 里的值 ——
     *       验收脚本里有 38 处直接引用它，全量改写脚本的回归风险远大于收益；</li>
     *   <li>而安全收益并不打折：攻击者要拿到"登录瞬间的响应体"必须先能注入脚本，
     *       那一刻他同样能读走表单里的密码 —— 真正的增量收益是"**日常长会话**里
     *       XSS 后门拿不到 refresh"（它在 HttpOnly Cookie 里，JS 永远读不到），
     *       这个收益在双轨下**完全成立**。</li>
     * </ul>
     *
     * <p>属性口径：HttpOnly（JS 不可读）、SameSite=Lax（挡跨站 POST，防 CSRF 触发刷新）、
     * Path=/api/auth（只随认证接口发送，其余请求不携带）、Secure 生产开启。
     * Max-Age 取 7 天：与 refresh 白名单 TTL 对齐；不同步只会导致"多登一次"，
     * 不会把会话置于危险状态。
     */
    private static final String REFRESH_COOKIE = "codelkup_refresh";
    private static final String REFRESH_COOKIE_PATH = "/api/auth";
    private static final Duration REFRESH_COOKIE_MAX_AGE = Duration.ofDays(7);

    /** 下发 / 续期 refresh Cookie（登录、注册、刷新成功时调用） */
    private void writeRefreshCookie(HttpServletResponse response, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE, refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .path(REFRESH_COOKIE_PATH)
                .sameSite("Lax")
                .maxAge(REFRESH_COOKIE_MAX_AGE)
                .build()
                .toString());
    }

    /** 清除 refresh Cookie（登出时调用；Max-Age=0 让浏览器立即丢弃） */
    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .path(REFRESH_COOKIE_PATH)
                .sameSite("Lax")
                .maxAge(0)
                .build()
                .toString());
    }

    @Operation(summary = "注册", description = "注册成功后直接返回双 token，免去二次登录；按 IP 限流")
    @PostMapping("/register")
    public Result<TokenResponse> register(@Valid @RequestBody RegisterRequest request,
                                          HttpServletRequest httpRequest,
                                          HttpServletResponse response) {
        // 注册开关（PRE-MORTEM T0-2）：放在限流**之前** —— "开关"是全局运营意图，
        // 关闭时应当对所有请求给出一致的 40304，而不是先烧注册额度、
        // 让访客在 42901 与 40304 之间看到两种失败（也让"关不关"与限流状态解耦）。
        if (!registrationEnabled) {
            throw new BusinessException(ErrorCode.REGISTRATION_CLOSED);
        }
        // ⚠️ 注册是"某邮箱是否已注册"的第二个出口：它必然要回答"这个邮箱能不能用"
        //    （EMAIL_ALREADY_EXISTS 与成功创建是可区分的两种结果）。check-email 已按 IP
        //    限流，若这里不限流，批量枚举与批量注册都能绕道这条路 —— 那道限流等于没做。
        //    用独立桶（不与 check-email 共用计数），避免正常注册被查重请求挤掉。
        if (!rateLimitService.tryAcquire("register", httpRequest.getRemoteAddr(), registerPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "注册过于频繁，请 1 小时后再试");
        }
        TokenResponse tokens = userService.register(request);
        // 双轨（见 REFRESH_COOKIE 注释）：body 照旧返回，同时落一枚 HttpOnly Cookie
        writeRefreshCookie(response, tokens.getRefreshToken());
        return Result.success(tokens);
    }

    @Operation(summary = "登录", description = "邮箱 + 密码登录，返回 accessToken / refreshToken")
    @PostMapping("/login")
    public Result<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest,
                                       HttpServletResponse response) {
        // ⚠️ 这个 IP 必须是**不可伪造**的：登录失败锁定按「IP + 邮箱」判重。
        //    可信边界交给容器配置（application.yml: server.forward-headers-strategy=native
        //    + remoteip.internal-proxies）—— 只有直连对端是可信代理时才采信
        //    X-Forwarded-For，否则用真实 socket 地址。nginx 侧也用 $remote_addr
        //    覆盖而非追加该头（见 nginx.conf），客户端伪造的值进不来。
        //
        //    历史坑：此前这里手写解析 XFF 并取**第一段**，客户端自带
        //    `X-Forwarded-For: 1.2.3.4` 即可让每次尝试落在不同计数键上，
        //    5 次锁定永不触发 —— 防爆破形同不存在。
        TokenResponse tokens = userService.login(request, httpRequest.getRemoteAddr());
        writeRefreshCookie(response, tokens.getRefreshToken());
        return Result.success(tokens);
    }

    @Operation(summary = "刷新凭证", description = "用 refreshToken 换一对新 token；按 IP 限流（默认 120 次/小时）。"
            + "入参双轨（第五轮审计 #3）：body 里的 refreshToken 优先，没有则读 HttpOnly Cookie（新前端走这条）")
    @PostMapping("/refresh")
    public Result<TokenResponse> refresh(@RequestBody(required = false) Map<String, String> body,
                                         @CookieValue(value = REFRESH_COOKIE, required = false) String cookieRefresh,
                                         HttpServletRequest httpRequest,
                                         HttpServletResponse response) {
        // L-4 补齐：这是未认证接口，每次调用都要做 JWT 解析 + Redis 读写 ——
        // 无限流时可用脚本放大后端成本；按 IP 限额，额度远高于正常设备（30 分钟一换 ≈ 2 次/小时）
        if (!rateLimitService.tryAcquire("refresh", httpRequest.getRemoteAddr(), refreshPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "刷新过于频繁，请稍后再试");
        }
        // 双轨取值：body 优先（旧前端与验收脚本），否则 Cookie（新前端，JS 读不到 refresh）
        String refreshToken = body == null ? null : body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshToken = cookieRefresh;
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            return Result.error(ErrorCode.PARAM_MISSING, "refreshToken 不能为空");
        }
        TokenResponse tokens = userService.refresh(refreshToken);
        // 轮换后的新 refresh 必须续写回 Cookie（否则 Cookie 里一直是已消费的旧值，下次刷新即失败）
        writeRefreshCookie(response, tokens.getRefreshToken());
        return Result.success(tokens);
    }

    @Operation(summary = "登出", description = "accessToken 拉黑 + refreshToken 白名单移除 + 清除 HttpOnly Cookie；"
            + "access 失效时按 refresh（body 优先、Cookie 兜底）反查用户，保证\"登出 = 服务端撤销\"不因 access 过期而落空")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @CurrentUser Long userId,
                               @RequestBody(required = false) Map<String, String> body,
                               @CookieValue(value = REFRESH_COOKIE, required = false) String cookieRefresh,
                               HttpServletResponse response) {
        // ⚠️ 安全审计 run-1 线索 1：access 过期/无效时 @CurrentUser 注入 null，
        //    UserServiceImpl.logout 内的撤销链（删 refresh 白名单 / 撤销水位 / SSE 关流）
        //    会被整段跳过 —— 用户以为已登出，refresh 白名单却还在（默认 7 天内仍可换新令牌）。
        //    这里补一条与 /refresh 同源的取值链（body 优先、Cookie 兜底）反解用户；
        //    只信签名有效的 refresh，伪造值解析为 null → 维持原 no-op；谁的值只注销谁自己的会话。
        Long effectiveUserId = userId != null ? userId : resolveUserIdFromRefresh(body, cookieRefresh);
        userService.logout(extractBearer(authorization), effectiveUserId);
        // 双轨收尾：前端已清本地存储，这里清服务端 Cookie
        clearRefreshCookie(response);
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

    /**
     * 登出兜底定位：从 refresh（body 优先、Cookie 兜底，与 /refresh 同源）解析归属用户。
     * 解析不到（缺失 / 伪造 / 非 refresh 类型 / 已过期）返回 null —— 调用方维持 no-op。
     */
    private Long resolveUserIdFromRefresh(Map<String, String> body, String cookieRefresh) {
        String refreshToken = body == null ? null : body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshToken = cookieRefresh;
        }
        return userService.resolveRefreshOwner(refreshToken);
    }

    /** 从 "Bearer xxx" 中剥离前缀；格式不对返回 null（登出接口容错，不因此报错） */
    private String extractBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7).trim();
    }
}
