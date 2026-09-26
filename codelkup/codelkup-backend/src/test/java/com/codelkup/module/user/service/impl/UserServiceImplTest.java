package com.codelkup.module.user.service.impl;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.user.dto.PasswordChangeRequest;
import com.codelkup.module.user.entity.User;
import com.codelkup.module.user.mapper.UserMapper;
import com.codelkup.security.JwtTokenProvider;
import com.codelkup.security.LoginAttemptService;
import com.codelkup.security.TokenStore;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 修改密码的单元测试。
 *
 * <p>钉死四条不变量（每一条都对应一种真实的账号接管路径）：
 * <ol>
 *   <li>原密码错 → 拒绝，且<b>不落库</b></li>
 *   <li>新密码与原密码相同 → 拒绝（用户会误以为改成功了）</li>
 *   <li>成功时新密码必须以<b>哈希</b>入库，绝不存明文</li>
 *   <li>成功后必须清 refresh 白名单 <b>且拉黑当前 accessToken</b> ——
 *       否则"改密"对当前这个被盗会话毫无作用</li>
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
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        tokenStore = mock(TokenStore.class);
        // PostMapper 只为公开主页的提问统计服务（M2-14 新增的构造参数），
        // 本测试覆盖的是改密流程，用 mock 传进去即可 —— 统计失败会被 Service 吞掉
        service = new UserServiceImpl(userMapper, passwordEncoder, jwtTokenProvider, tokenStore,
                mock(LoginAttemptService.class), mock(PostMapper.class));

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

    /** Mockito 的 argThat 需要显式类型推断，这里包一层避免 import 噪音 */
    private static User argThat(java.util.function.Predicate<User> predicate) {
        return org.mockito.ArgumentMatchers.argThat(predicate::test);
    }
}