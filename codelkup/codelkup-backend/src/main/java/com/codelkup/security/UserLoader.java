package com.codelkup.security;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.module.user.entity.User;
import com.codelkup.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 根据 JWT 中的 userId 加载用户。
 *
 * <p><b>设计取舍</b>：accessToken 是无状态的，本可以完全不查库。
 * 但这里每次请求都查一次用户，原因是：
 * <ol>
 *   <li>需要感知<b>封禁/禁言</b>的即时生效 —— 否则被封用户拿旧 token 仍能操作。</li>
 *   <li>获取用户的 role 用于权限判断。</li>
 * </ol>
 *
 * <p>性能上这确实是一次查库，但走主键索引、开销极小。
 * 若将来成为瓶颈，可在 Redis 缓存用户基本信息（V0.2 优化点）——
 * 这正是"先朴素实现，再按数据优化"的一个实例。
 *
 * @author 刘
 */
@Service
@RequiredArgsConstructor
public class UserLoader {

    private final UserMapper userMapper;

    public User loadById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    /**
     * 将 role 映射为 Spring Security 权限。
     *
     * <p>虽然当前只有简单的角色区分，仍走标准 GrantedAuthority，
     * 便于后续用 {@code @PreAuthorize("hasRole('ADMIN')")} 声明式控权。
     */
    public List<SimpleGrantedAuthority> toAuthorities(User user) {
        String role = switch (user.getRole() == null ? 0 : user.getRole()) {
            case 2 -> "ADMIN";
            case 1 -> "MODERATOR";
            default -> "USER";
        };
        // Spring Security 的 hasRole('X') 期望权限名形如 ROLE_X
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
