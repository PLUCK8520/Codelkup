package com.codelkup.security;

import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 认证/授权失败的 JSON 响应处理器。
 *
 * <p><b>为什么需要它们？</b>
 * Spring Security 默认的重定向到登录页 或 返回空 403 页面，对前后端分离的 API 都不合适。
 * 前端需要的是统一结构的 JSON，才能走同一套错误处理逻辑。
 *
 * @author 刘
 */
@Component
@RequiredArgsConstructor
public class SecurityHandlers {

    private final ObjectMapper objectMapper;

    /** 未认证（401）—— 未登录或 token 失效 */
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (HttpServletRequest request, HttpServletResponse response,
                AuthenticationException authException) -> write(response, ErrorCode.UNAUTHORIZED);
    }

    /** 无权限（403）—— 已登录但权限不足 */
    public AccessDeniedHandler accessDeniedHandler() {
        return (HttpServletRequest request, HttpServletResponse response,
                AccessDeniedException accessDeniedException) -> write(response, ErrorCode.FORBIDDEN);
    }

    private void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        // 注意：HTTP 状态码与业务码分离（见 DEV-DOC §11.1），
        // 这里 HTTP 层仍返回 200，业务结果由 code 字段表达，便于前端统一拦截。
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(Result.error(errorCode))
        );
    }
}
