package com.codelkup.config;

import com.codelkup.security.JwtAuthenticationFilter;
import com.codelkup.security.SecurityHandlers;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
                        .requestMatchers("/api/auth/**").permitAll()

                        // ---- 系统接口 ----
                        .requestMatchers("/api/system/health").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()

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
