package com.codelkup.module.user.service;

import com.codelkup.module.user.dto.LoginRequest;
import com.codelkup.module.user.dto.RegisterRequest;
import com.codelkup.module.user.dto.TokenResponse;
import com.codelkup.module.user.entity.User;
import com.codelkup.module.user.vo.CurrentUserVO;
import com.codelkup.module.user.vo.RankingUserVO;
import com.codelkup.module.user.vo.UserVO;

import java.util.List;

/**
 * 用户与认证服务。
 *
 * @author 刘
 */
public interface UserService {

    /** 注册并自动登录（返回双 token） */
    TokenResponse register(RegisterRequest request);

    /** 邮箱 + 密码登录 */
    TokenResponse login(LoginRequest request, String clientIp);

    /** 用 refreshToken 换取新的 accessToken */
    TokenResponse refresh(String refreshToken);

    /** 登出：accessToken 拉黑 + refreshToken 白名单移除 */
    void logout(String accessToken, Long userId);

    /**
     * 修改密码。
     *
     * @param accessToken 当前请求携带的 accessToken（改密后要把它一起拉黑，
     *                    强制本人也用新密码重新登录 —— 否则改密对"当前这个会话"毫无意义）
     */
    void changePassword(Long userId, com.codelkup.module.user.dto.PasswordChangeRequest request, String accessToken);

    /** 邮箱是否已被占用 */
    boolean isEmailTaken(String email);

    /** 按 id 查询用户实体（不存在则抛 404） */
    User getEntityById(Long id);

    /** 公开主页信息（不含邮箱） */
    UserVO getPublicProfile(Long id);

    /** 本人资料（含邮箱） */
    CurrentUserVO getCurrentUserProfile(Long id);

    /** 修改个人资料（只更新传入的非 null 字段） */
    CurrentUserVO updateProfile(Long userId, com.codelkup.module.user.dto.ProfileUpdateRequest request);

    /** 更新头像 URL */
    CurrentUserVO updateAvatar(Long userId, String avatarUrl);

    /**
     * 声望榜（只读，公开）。
     *
     * @param limit 取前 N 名（1~50，上限由 Controller 的参数校验保证）
     */
    List<RankingUserVO> topByReputation(int limit);

    /** 某用户的公开帖子由 PostService 提供，此处仅暴露必要的用户查询 */
}