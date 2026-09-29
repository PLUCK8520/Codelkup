package com.codelkup.module.user.service.impl;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.user.dto.PasswordChangeRequest;
import com.codelkup.module.user.entity.User;
import com.codelkup.module.user.event.UserSessionRevokedEvent;
import com.codelkup.module.user.mapper.UserMapper;
import com.codelkup.security.JwtTokenProvider;
import com.codelkup.security.LoginAttemptService;
import com.codelkup.security.TokenStore;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 修改密码的单元测试。
 *
 * <p>钉死五条不变量（每一条都对应一种真实的账号接管路径）：
 * <ol>
 *   <li>原密码错 → 拒绝，且<b>不落库</b></li>
 *   <li>新密码与原密码相同 → 拒绝（用户会误以为改成功了）</li>
 *   <li>成功时新密码必须以<b>哈希</b>入库，绝不存明文</li>
 *   <li>成功后必须清 refresh 白名单 <b>且拉黑当前 accessToken</b> ——
 *       否则"改密"对当前这个被盗会话毫无作用</li>
 *   <li>成功后必须广播 {@link UserSessionRevokedEvent}（F2）—— HTTP 凭证撤销管不到
 *       已建立的 SSE 长连接，不广播则被盗会话继续白拿实时通知</li>
 * </ol>
 */
class UserServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final String OLD_HASH = "$2a$10$OLDHASH";
    private static final String NEW_HASH = "$2a$10$NEWHASH";
    private static final String TOKEN = "header.payload.signature";

    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private TokenStore tokenStore;
    private ApplicationEventPublisher eventPublisher;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        tokenStore = mock(TokenStore.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        // PostMapper 只为公开主页的提问统计服务（M2-14 新增的构造参数），
        // 本测试覆盖的是改密流程，用 mock 传进去即可 —— 统计失败会被 Service 吞掉
        service = new UserServiceImpl(userMapper, passwordEncoder, jwtTokenProvider, tokenStore,
                mock(LoginAttemptService.class), mock(PostMapper.class), eventPublisher);

        User user = new User();
        user.setId(USER_ID);
        user.setPassword(OLD_HASH);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
    }

    private PasswordChangeRequest request(String oldPwd, String newPwd) {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setOldPassword(oldPwd);
        req.setNewPassword(newPwd);
        return req;
    }

    @Test
    void wrongOldPassword_isRejected_andNothingIsUpdated() {
        when(passwordEncoder.matches("wrong-old", OLD_HASH)).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.changePassword(USER_ID, request("wrong-old", "newpass123"), TOKEN));

        assertEquals(ErrorCode.BAD_CREDENTIALS, e.getErrorCode());
        verify(userMapper, never()).updateById(any(User.class));
        verify(tokenStore, never()).removeRefreshToken(any());
        // 失败路径不得动任何撤销动作（计数/黑名单/水位/事件全都不该发生）
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void sameAsOldPassword_isRejected() {
        when(passwordEncoder.matches("oldpass123", OLD_HASH)).thenReturn(true);
        when(passwordEncoder.matches("oldpass123", OLD_HASH)).thenReturn(true); // 新 = 旧

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.changePassword(USER_ID, request("oldpass123", "oldpass123"), TOKEN));

        assertEquals(ErrorCode.PARAM_ERROR, e.getErrorCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void success_storesHash_andInvalidatesEveryCredential() {
        when(passwordEncoder.matches("oldpass123", OLD_HASH)).thenReturn(true);
        when(passwordEncoder.matches("newpass456", OLD_HASH)).thenReturn(false);
        when(passwordEncoder.encode("newpass456")).thenReturn(NEW_HASH);
        Claims claims = mock(Claims.class);
        when(jwtTokenProvider.parse(TOKEN)).thenReturn(claims);

        service.changePassword(USER_ID, request("oldpass123", "newpass456"), TOKEN);

        // 落库的是哈希，不是明文
        verify(userMapper).updateById(argThat(user -> NEW_HASH.equals(user.getPassword())));
        // 全端失效：refresh 白名单清空 + 当前 accessToken 拉黑
        verify(tokenStore).removeRefreshToken(USER_ID);
        verify(tokenStore).blacklistAccessToken(claims);
        // L-3 水位：把其他设备手里还没过期的 access 一并作废（只断言被调用，水位的秒值由集成脚本验证）
        verify(tokenStore).revokeAccessBefore(eq(USER_ID), anyLong());
        // F2：广播会话撤销事件 —— 由通知模块监听并关闭该用户全部 SSE 长连接
        verify(eventPublisher).publishEvent(new UserSessionRevokedEvent(USER_ID));
    }

    @Test
    void withoutAccessToken_stillClearsRefreshWhitelist() {
        when(passwordEncoder.matches("oldpass123", OLD_HASH)).thenReturn(true);
        when(passwordEncoder.matches("newpass456", OLD_HASH)).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn(NEW_HASH);

        service.changePassword(USER_ID, request("oldpass123", "newpass456"), null);

        verify(tokenStore).removeRefreshToken(USER_ID);
        // 没有 token 就没什么可拉黑的：不应去解析 null，也不该抛异常
        verify(jwtTokenProvider, never()).parse(anyString());
        verify(tokenStore, never()).blacklistAccessToken(any());
    }

    @Test
    void resolveRefreshOwner_validRefreshToken_returnsUserId() {
        Claims claims = mock(Claims.class);
        when(jwtTokenProvider.parse("r-token")).thenReturn(claims);
        when(jwtTokenProvider.isRefreshToken(claims)).thenReturn(true);
        when(jwtTokenProvider.getUserId(claims)).thenReturn(USER_ID);

        assertEquals(USER_ID, service.resolveRefreshOwner("r-token"));
    }

    /**
     * 登出兜底定位（安全审计 run-1 线索 1）：只信"签名有效 + typ=refresh"的 token。
     * 伪造/过期（parse → null）、access 类型、空值一律返回 null —— 维持原 no-op，
     * 不给攻击者任何"用垃圾值触发别人的撤销"的入口。
     */
    @Test
    void resolveRefreshOwner_invalidAccessOrBlank_returnsNull() {
        when(jwtTokenProvider.parse("garbage")).thenReturn(null);
        assertNull(service.resolveRefreshOwner("garbage"));

        Claims accessClaims = mock(Claims.class);
        when(jwtTokenProvider.parse("a-token")).thenReturn(accessClaims);
        when(jwtTokenProvider.isRefreshToken(accessClaims)).thenReturn(false);
        assertNull(service.resolveRefreshOwner("a-token"));

        assertNull(service.resolveRefreshOwner(null));
        assertNull(service.resolveRefreshOwner("   "));
    }

    /** Mockito 的 argThat 需要显式类型推断，这里包一层避免 import 噪音 */
    private static User argThat(java.util.function.Predicate<User> predicate) {
        return org.mockito.ArgumentMatchers.argThat(predicate::test);
    }
}