package com.codelkup.module.user.service.impl;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.module.user.dto.LoginRequest;
import com.codelkup.module.user.dto.ProfileUpdateRequest;
import com.codelkup.module.user.dto.RegisterRequest;
import com.codelkup.module.user.dto.TokenResponse;
import com.codelkup.module.user.entity.User;
import com.codelkup.module.user.mapper.UserMapper;
import com.codelkup.module.user.service.UserService;
import com.codelkup.module.user.vo.CurrentUserVO;
import com.codelkup.module.user.vo.UserVO;
import com.codelkup.security.JwtTokenProvider;
import com.codelkup.security.LoginAttemptService;
import com.codelkup.security.TokenStore;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户与认证服务实现。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenStore tokenStore;
    private final LoginAttemptService loginAttemptService;

    // ==================== 注册 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TokenResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());

        // 预检查：给出友好提示（真正的唯一性保障靠下面的唯一索引）
        if (userMapper.countByEmail(email) > 0) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname().trim());
        user.setRole(0);
        user.setStatus(0);
        user.setReputation(0);
        user.setLevel(1);
        user.setLastActive(LocalDateTime.now());

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册同一邮箱：预检查通过但插入时撞唯一索引。
            // 靠数据库约束兜底，而不是靠应用层判断 —— 应用层判断存在并发窗口。
            log.warn("并发注册撞唯一索引 | email={}", email);
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        log.info("用户注册成功 | id={} | nickname={}", user.getId(), user.getNickname());

        // 注册即自动登录
        return issueTokens(user);
    }

    // ==================== 登录 ====================

    @Override
    public TokenResponse login(LoginRequest request, String clientIp) {
        String email = normalizeEmail(request.getEmail());

        loginAttemptService.checkNotLocked(clientIp, email);

        User user = userMapper.selectByEmail(email);
        // ⚠️ 安全要点：邮箱不存在与密码错误返回**同一条**错误信息，
        //    否则攻击者可据此枚举出哪些邮箱已注册。
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptService.recordFailure(clientIp, email);
            log.warn("登录失败 | email={} | ip={}", email, clientIp);
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }

        if (user.getStatus() != null && user.getStatus() == 2) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        loginAttemptService.clearFailure(clientIp, email);

        // 更新最后活跃时间（失败不影响登录，所以不放在事务里）
        User touch = new User();
        touch.setId(user.getId());
        touch.setLastActive(LocalDateTime.now());
        userMapper.updateById(touch);

        log.info("登录成功 | id={} | ip={}", user.getId(), clientIp);
        return issueTokens(user);
    }

    // ==================== 刷新 ====================

    @Override
    public TokenResponse refresh(String refreshToken) {
        Claims claims = jwtTokenProvider.parse(refreshToken);
        if (claims == null || !jwtTokenProvider.isRefreshToken(claims)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        Long userId = jwtTokenProvider.getUserId(claims);
        if (userId == null || !tokenStore.isRefreshTokenValid(userId, refreshToken)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        User user = userMapper.selectById(userId);
        if (user == null || (user.getStatus() != null && user.getStatus() == 2)) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        // 重新签发一对新 token（refresh 也轮换，安全性更高）
        return issueTokens(user);
    }

    // ==================== 登出 ====================

    @Override
    public void logout(String accessToken, Long userId) {
        if (accessToken != null) {
            Claims claims = jwtTokenProvider.parse(accessToken);
            if (claims != null) {
                tokenStore.blacklistAccessToken(claims);
            }
        }
        if (userId != null) {
            tokenStore.removeRefreshToken(userId);
        }
        log.info("登出 | userId={}", userId);
    }

    // ==================== 查询 ====================

    @Override
    public boolean isEmailTaken(String email) {
        return userMapper.countByEmail(normalizeEmail(email)) > 0;
    }

    @Override
    public User getEntityById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    @Override
    public UserVO getPublicProfile(Long id) {
        return toPublicVO(getEntityById(id));
    }

    @Override
    public CurrentUserVO getCurrentUserProfile(Long id) {
        return toCurrentVO(getEntityById(id));
    }

    // ==================== 资料编辑 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CurrentUserVO updateProfile(Long userId, ProfileUpdateRequest request) {
        // 先确认用户存在（顺带拿到最新状态）
        getEntityById(userId);

        User update = new User();
        update.setId(userId);

        // MyBatis-Plus 的 updateById 默认忽略 null 字段，天然是"部分更新"
        if (request.getNickname() != null) {
            update.setNickname(request.getNickname().trim());
        }
        if (request.getBio() != null) {
            // 允许传空串来"清空简介"：空串不是 null，会被正常写入
            update.setBio(request.getBio().trim());
        }
        if (request.getTechTags() != null) {
            update.setTechTags(normalizeTechTags(request.getTechTags()));
        }

        userMapper.updateById(update);
        log.info("修改资料 | userId={}", userId);

        return toCurrentVO(getEntityById(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CurrentUserVO updateAvatar(Long userId, String avatarUrl) {
        getEntityById(userId);

        User update = new User();
        update.setId(userId);
        update.setAvatar(avatarUrl);
        userMapper.updateById(update);

        log.info("更新头像 | userId={} | url={}", userId, avatarUrl);
        return toCurrentVO(getEntityById(userId));
    }

    /**
     * 规范化技术标签：去空白、去空项、去重（大小写不敏感），上限 10 个。
     *
     * <p>与标签表不同，这里是<b>用户自由文本</b>（逗号分隔存在 t_user.tech_tags），
     * 不做"入库建标签"，只做清洗 —— 避免个人简介污染主标签体系。
     */
    private String normalizeTechTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        var seen = new java.util.LinkedHashSet<String>();
        var result = new java.util.ArrayList<String>();
        for (String part : raw.split("[,，]")) {
            String tag = part.trim();
            if (tag.isEmpty()) {
                continue;
            }
            if (tag.length() > 20) {
                tag = tag.substring(0, 20);
            }
            if (seen.add(tag.toLowerCase())) {
                result.add(tag);
            }
            if (result.size() >= 10) {
                break;
            }
        }
        return String.join(",", result);
    }

    // ==================== 私有工具 ====================

    /**
     * 邮箱规范化。
     *
     * <p>统一转小写 + 去空格 —— 否则 {@code A@x.com} 与 {@code a@x.com}
     * 会被当成两个账号，且唯一索引拦不住。
     */
    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(user.getId());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());
        tokenStore.saveRefreshToken(user.getId(), refreshToken);
        return new TokenResponse(
                accessToken, refreshToken,
                jwtTokenProvider.getAccessTtlSeconds(),
                toPublicVO(user)
        );
    }

    private UserVO toPublicVO(User user) {
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }

    private CurrentUserVO toCurrentVO(User user) {
        CurrentUserVO vo = new CurrentUserVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }
}
