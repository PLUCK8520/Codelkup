package com.codelkup.security;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.module.user.entity.User;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 解析 {@link CurrentUser} 注解标注的参数。
 *
 * @author 刘
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        CurrentUser annotation = parameter.getParameterAnnotation(CurrentUser.class);
        boolean required = annotation != null && annotation.required();

        User user = currentUserOrNull();

        if (user == null && required) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        Class<?> type = parameter.getParameterType();

        if (User.class.isAssignableFrom(type)) {
            return user;
        }
        if (Long.class.isAssignableFrom(type)) {
            return user == null ? null : user.getId();
        }

        throw new IllegalStateException(
                "@CurrentUser 仅支持 User 或 Long 类型参数，当前为 " + type.getSimpleName());
    }

    private User currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof AuthPrincipal principal) {
            return principal.getUser();
        }
        return null;
    }
}
