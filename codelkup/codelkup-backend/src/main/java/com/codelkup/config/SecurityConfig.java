package com.codelkup.config;

import com.codelkup.security.JwtAuthenticationFilter;
import com.codelkup.security.SecurityHandlers;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置。
 *
 * <p>Sprint 1 起已<b>收紧权限</b>：公开接口显式放行，其余一律要求认证。
 *
 * <p>授权规则设计原则：<b>白名单式</b>（显式放行公开接口，其余默认拒绝），
 * 而不是黑名单式（默认放行，逐个关闭）——后者新增接口时容易忘记加保护。
 *
 * @author 刘
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity      // 启用 @PreAuthorize，便于后续细粒度控权
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityHandlers securityHandlers;

    @Value("${codelkup.cors.allowed-origins}")
    private String allowedOrigins;

    /**
     * BCrypt 密码编码器。
     *
     * <p>强度 10（默认）是安全性与性能的平衡点：单次哈希约 50~100ms，
     * 足以抵抗暴力破解，又不至于拖慢登录接口。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 前后端分离 + JWT，无需 CSRF token（无 Cookie 会话）
                .csrf(AbstractHttpConfigurer::disable)
                // 无状态会话
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CORS
                .cors(c -> c.configurationSource(corsConfigurationSource()))

                // ============ 授权规则 ============
                .authorizeHttpRequests(auth -> auth
                        // ---- 预检请求 ----
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // ---- 认证接口：完全公开 ----
                        // ⚠️ 例外必须排在它前面：签发 SSE ticket 需要登录态。
                        //    ticket 是给 EventSource 用的短期凭证（它无法带 Header），
                        //    签发它的人必须先是已登录用户；Spring Security 按声明顺序匹配，
                        //    这一条若写到 /api/auth/** 之后就永远轮不到。
                        .requestMatchers(HttpMethod.POST, "/api/auth/sse-ticket").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()

                        // ---- 系统接口 ----
                        .requestMatchers("/api/system/health", "/api/system/config").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                        // ---- 指标端点：只对"内部来源"开放（M3-10）----
                        // 为什么不是 permitAll：指标里有业务量级、错误分布与调用成本，
                        // "公网抓不到"应当由**这里**保证，而不是只靠"部署时没人把端口暴露出去"。
                        // 为什么按来源地址而不是登录态：Prometheus 抓取器没有本站账号体系，
                        // 而它只可能位于容器网络或本机 —— 来源地址就是最贴合的凭证。
                        // ⚠️ 经 nginx 转发时 getRemoteAddr() 拿到的是 XFF 里的**真实客户端地址**
                        //    （server.forward-headers-strategy=native，且直连对端是可信私网代理），
                        //    因此即便公网请求被反代到后端也会因源地址是公网而被拒；
                        //    nginx 侧另有一条 `location /actuator/ { return 404; }` 作为第一道。
                        .requestMatchers("/actuator/prometheus")
                        .access((authentication, context) ->
                                new AuthorizationDecision(isInternalAddress(context.getRequest())))

                        // ---- API 文档 ----
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/swagger-resources/**",
                                "/webjars/**"
                        ).permitAll()

                        // ---- 上传的静态资源（头像等）----
                        .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()

                        // ---- 游客可读的内容（GET 只读接口）----
                        // 站点汇总统计：首页 / 问答 / 标签 / 板块页的公共计数，
                        // 数字全部来自游客本就能看到的列表与字典，单独放行。
                        .requestMatchers(HttpMethod.GET, "/api/stats").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/boards/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tags/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/posts/**").permitAll()
                        // ⚠️ 上面这行同时覆盖了 Sprint 3 的
                        //    GET /api/posts/{postId}/comments —— 评论列表游客可读，
                        //    与"帖子列表可读"保持一致（不强制登录才能看讨论）。
                        //    而写操作 POST/DELETE /api/posts/{id}/like|collect 是
                        //    非 GET，落到 anyRequest().authenticated()，需登录。
                        .requestMatchers(HttpMethod.GET, "/api/search/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/feed/**").permitAll()
                        // ---- SSE 通知流：鉴权改走一次性 ticket ----
                        // ⚠️ 放行的是"过滤器链的授权"，不是"免鉴权"：EventSource 不能带
                        //    Authorization 头，所以凭证（一次性 ticket）由 SseController
                        //    在处理器内校验 —— 没有合法 ticket 一律 40101。
                        .requestMatchers(HttpMethod.GET, "/api/notifications/stream").permitAll()
                        // 用户主页：公开可读（返回 UserVO，不含邮箱），
                        // 与 /api/users/me 形成对照 —— 后者含邮箱，必须登录。
                        .requestMatchers(HttpMethod.GET, "/api/users/*/posts").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/users/*/followers").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/users/*/followees").permitAll()
                        // 关注状态与粉丝计数：粉丝数是公开数据（用户主页对游客可见），
                        // 计数部分游客也能看；只有 following 字段依赖登录态，
                        // 未登录时返回 null（由 Service 处理，不是 401）。
                        .requestMatchers(HttpMethod.GET, "/api/users/*/follow-state").permitAll()
                        // ⚠️ 顺序要紧：/api/users/me 必须排在 /api/users/* 之前，
                        //    否则 "me" 会被通配符匹配成 id 而被放行。
                        .requestMatchers(HttpMethod.GET, "/api/users/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/users/*").permitAll()

                        // ---- 管理面：路径级角色约束（安全审计 run-1 加固）----
                        // 管理端点本就有方法级 @PreAuthorize("hasRole('ADMIN')")（第二道），
                        // 这里补上过滤器链这一道：将来新增管理端点若漏标注解，也不会
                        // 向任何已登录用户开放（默认拒绝的边界从"白名单式"延到方法级之外）。
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // ---- 其余全部需要认证 ----
                        // 注意：未匹配到任何 Controller 的路径也会落到这里，
                        // 因此"访问不存在的接口"在未登录时返回 40101 而非 40001。
                        // 这是有意的：不向未认证调用者泄露"哪些路径存在"。
                        .anyRequest().authenticated()
                )

                // 认证/授权失败的统一 JSON 响应
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityHandlers.authenticationEntryPoint())
                        .accessDeniedHandler(securityHandlers.accessDeniedHandler())
                )

                // JWT 过滤器插在用户名密码过滤器之前
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                // 关闭默认的 formLogin / httpBasic（API 服务不需要）
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);

        return http.build();
    }

    /**
     * 是否为"内部来源"地址：回环 + RFC1918 私网（本机 / 容器网络 / 局域网）。
     *
     * <p>只认这四个网段，其余（公网地址、以及无法归入私网的 IPv6）一律拒绝 ——
     * 与本类的白名单风格一致：默认拒绝，显式放行。
     */
    private static boolean isInternalAddress(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (ip == null) {
            return false;
        }
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return true;   // IPv6 回环（Windows 上实测会写成这种全零展开形式）
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;   // 其它 IPv6 地址：本项目部署未使用 IPv6 私网
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 127                                  // 127.0.0.0/8   回环
                    || a == 10                               // 10.0.0.0/8
                    || (a == 172 && b >= 16 && b <= 31)      // 172.16.0.0/12 Docker 网桥默认落在此段
                    || (a == 192 && b == 168);               // 192.168.0.0/16
        } catch (NumberFormatException e) {
            return false;   // 非法段（比如被塞了字母）→ 拒绝
        }
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // 暴露 Authorization 头，便于前端读取
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
