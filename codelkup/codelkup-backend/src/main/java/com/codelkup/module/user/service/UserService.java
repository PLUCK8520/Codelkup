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
     * 从 refreshToken 解析其归属用户 —— 仅供登出兜底定位使用（安全审计 run-1 线索 1）。
     *
     * <p>为什么需要：登出接口的撤销链以 {@code userId} 为触发条件，而该 userId 来自
     * accessToken 的解析结果；access 过期/无效时它会缺失，导致"用户以为登出了、
     * refresh 白名单却还在（7 天内仍可换新令牌）"。logout 端点因此按"body 优先、
     * Cookie 兜底"取 refresh 并反解用户（与 {@link #refresh} 同一取值链）。
     *
     * <p>安全语义：只信签名有效且 {@code typ=refresh} 的 token（伪造/篡改解析为
     * null → 维持原 no-op）；不校验白名单槽位（登出本就要删它，槽位已被轮换时
     * 同样应允许撤销）；谁的值只能注销谁自己的会话，不存在越权面。
     *
     * @return 归属用户 id；无法解析时返回 null
     */
    Long resolveRefreshOwner(String refreshToken);

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