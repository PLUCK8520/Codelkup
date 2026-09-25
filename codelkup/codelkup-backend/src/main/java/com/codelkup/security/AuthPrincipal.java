package com.codelkup.security;

import com.codelkup.module.user.entity.User;
import lombok.Getter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * 认证主体（Principal）。
 *
 * <p>存放已登录用户的实体，供 Controller 通过 {@code @CurrentUser} 直接取用，
 * 避免各处重复从 SecurityContext 手工解析。
 *
 * @author 刘
 */
@Getter
public class AuthPrincipal extends UsernamePasswordAuthenticationToken {

    private final User user;

    public AuthPrincipal(User user, Object credentials, Collection<? extends GrantedAuthority> authorities) {
        super(user, credentials, authorities);
        this.user = user;
    }

    public Long getUserId() {
        return user == null ? null : user.getId();
    }
}
