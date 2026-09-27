package com.codelkup.security;

import com.codelkup.module.user.entity.User;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器。
 *
 * <p>职责单一：从请求头取出 Bearer token → 校验 → 写入 SecurityContext。
 * <b>不做放行/拒绝的决定</b>，那是 SecurityConfig 授权的职责。
 *
 * <p>关键行为：token 无效时**不抛异常、不直接返回错误**，而是保持匿名身份继续走过滤链，
 * 让后续授权规则决定是放行（公开接口）还是拒绝（受保护接口）。
 * 否则公开接口在携带坏 token 时会被误拒。
 *
 * @author 刘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenStore tokenStore;
    private final UserLoader userLoader;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = resolveToken(request);

        if (StringUtils.hasText(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
            Claims claims = jwtTokenProvider.parse(token);

            // 必须同时满足：解析成功 + 是 accessToken + 未被拉黑
            if (claims != null
                    && jwtTokenProvider.isAccessToken(claims)
                    && !tokenStore.isBlacklisted(claims.getId())) {

                Long userId = jwtTokenProvider.getUserId(claims);

                // L-3：用户级撤销水位（改密时写入）—— issuedAt 早于水位的一律拒绝。
                // 与 jti 黑名单互补：黑名单管"单枚"（登出），水位管"一整代"（改密后的他设备会话）。
                Long revokedBefore = userId == null ? null : tokenStore.revokedBefore(userId);
                boolean revoked = revokedBefore != null
                        && claims.getIssuedAt() != null
                        && claims.getIssuedAt().getTime() / 1000 < revokedBefore;

                if (revoked) {
                    log.debug("token 已被用户级撤销（改密）| userId={}", userId);
                } else if (userId != null) {
                    try {
                        User user = userLoader.loadById(userId);

                        // 封禁用户：立即失效，即使 token 仍在有效期内
                        if (user.getStatus() != null && user.getStatus() == 2) {
                            log.debug("已封禁用户尝试访问 | userId={}", userId);
                        } else {
                            AuthPrincipal principal = new AuthPrincipal(
                                    user, null, userLoader.toAuthorities(user));
                            SecurityContextHolder.getContext().setAuthentication(principal);
                        }
                    } catch (Exception e) {
                        // 用户不存在等情况：保持匿名，不阻断请求
                        log.debug("加载用户失败 | userId={} | {}", userId, e.getMessage());
                    }
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 优先从 Header 取；其次允许从 query 参数取
     * （为将来 WebSocket 握手等无法设置 Header 的场景预留）。
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length()).trim();
        }
        return null;
    }
}
