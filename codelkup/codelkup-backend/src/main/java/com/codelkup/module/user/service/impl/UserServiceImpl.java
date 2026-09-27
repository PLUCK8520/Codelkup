package com.codelkup.module.user.service.impl;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.module.content.mapper.PostMapper;
import com.codelkup.module.user.dto.LoginRequest;
import com.codelkup.module.user.dto.PasswordChangeRequest;
import com.codelkup.module.user.dto.ProfileUpdateRequest;
import com.codelkup.module.user.dto.RegisterRequest;
import com.codelkup.module.user.dto.TokenResponse;
import com.codelkup.module.user.entity.User;
import com.codelkup.module.user.event.UserSessionRevokedEvent;
import com.codelkup.module.user.mapper.UserMapper;
import com.codelkup.module.user.service.UserService;
import com.codelkup.module.user.vo.CurrentUserVO;
import com.codelkup.module.user.vo.RankingUserVO;
import com.codelkup.module.user.vo.UserVO;
import com.codelkup.security.JwtTokenProvider;
import com.codelkup.security.LoginAttemptService;
import com.codelkup.security.TokenStore;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
    /** 只用于主页的提问统计（一条 COUNT 聚合），见 fillQuestionStats 的说明 */
    private final PostMapper postMapper;
    /** 会话撤销后广播 {@link UserSessionRevokedEvent}，由通知模块监听并关闭 SSE 长连接（F2） */
    private final ApplicationEventPublisher eventPublisher;

    // ==================== 声望榜（V0.4） ====================

    /**
     * 声望榜（只读）。
     *
     * <p>名次在服务端写进 VO，而不是让前端用数组下标 +1：
     * 名次是榜单的核心数据，且将来若给榜单加分页，名次必须等于"偏移量 + 下标"，
     * 那时前端算就错了。多写这三行，换来的是"前端永远不用知道分页细节"。
     */
    @Override
    public List<RankingUserVO> topByReputation(int limit) {
        List<RankingUserVO> list = userMapper.selectTopByReputation(limit);
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setRank(i + 1);
        }
        return list;
    }

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
        if (userId == null) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        User user = userMapper.selectById(userId);
        if (user == null || (user.getStatus() != null && user.getStatus() == 2)) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        // 签发新对，再**原子 CAS 轮换**白名单（F1，第二轮审计）：校验与写回必须是
        // 一个动作 —— 否则与改密的"删除白名单"并发时，删除后的写回会让被盗 refresh
        // 的会话复活（"改密=踢下线"失效）。
        TokenResponse tokens = buildTokens(user);
        if (!tokenStore.rotateRefreshToken(userId, refreshToken, tokens.getRefreshToken())) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        return tokens;
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

    // ==================== 修改密码 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, PasswordChangeRequest request, String accessToken) {
        // 失败锁定（M-1 修复）：与登录同一口径 —— 先判锁定、再做密码比对。
        // 少了这道闸门，持有有效凭证者可无限次猜"原密码"，猜中即改密接管账号
        // （安全审计实测：修复前连续 8 次错误原密码全部 40104，永不锁定）。
        loginAttemptService.checkPasswordChangeNotLocked(userId);

        User user = getEntityById(userId);

        // 第一步：验原密码。既是安全闸门，也是"操作者确实是本人"的二次确认
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            loginAttemptService.recordPasswordChangeFailure(userId);
            log.warn("修改密码失败：原密码不正确 | userId={} | 已累加失败计数", userId);
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS, "原密码不正确");
        }
        // 比对通过即清零（与 login 相同："成功一次就清零"）；
        // 下面的"新旧相同"属于参数问题而非凭证失败，不应计数
        loginAttemptService.clearPasswordChangeFailure(userId);
        // 新旧相同没有意义，而且会让用户误以为"改了"（真实场景里很常见）
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "新密码不能与原密码相同");
        }

        User update = new User();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(update);

        // 第二步：让所有既有凭证失效（四条一起，才配得上"改密=把别人踢下线"）。
        //   1) 清 refresh 白名单 —— 其他设备再也不能刷新（本项目是单槽位，等于全端下线）
        //   2) 拉黑当前 accessToken —— 连"本次请求携带的这枚"也立即作废
        //   3) 用户级撤销水位（L-3 修复）—— 把**其他设备**手里还没过期的 access 一并作废：
        //      只有 (1)(2) 时，别的设备还能拿旧 access 活到自然过期（≤30 分钟）。
        //      水位按秒记、过滤器严格小于才拒 —— 本秒内新登录签发的 token 不受影响。
        //   4) 广播会话撤销事件（F2 修复）—— 上面三条管住的是"新的 HTTP 请求"，
        //      管不住已经建立的 SSE 长连接（握手后不再校验凭证）。由通知模块的
        //      监听器关掉该用户全部 SSE 流，否则被盗会话能继续白拿实时通知。
        tokenStore.removeRefreshToken(userId);
        if (accessToken != null) {
            Claims claims = jwtTokenProvider.parse(accessToken);
            if (claims != null) {
                tokenStore.blacklistAccessToken(claims);
            }
        }
        tokenStore.revokeAccessBefore(userId, Instant.now().getEpochSecond());
        eventPublisher.publishEvent(new UserSessionRevokedEvent(userId));

        log.info("修改密码 | userId={} | 已清除 refresh 白名单、拉黑当前 accessToken、写入撤销水位并关闭 SSE 连接", userId);
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
        TokenResponse tokens = buildTokens(user);
        tokenStore.saveRefreshToken(user.getId(), tokens.getRefreshToken());
        return tokens;
    }

    /** 只签发、不落白名单 —— refresh 的 CAS 轮换需要自己原子写回（F1） */
    private TokenResponse buildTokens(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(user.getId());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());
        return new TokenResponse(
                accessToken, refreshToken,
                jwtTokenProvider.getAccessTtlSeconds(),
                toPublicVO(user)
        );
    }

    private UserVO toPublicVO(User user) {
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        fillQuestionStats(vo, user.getId());
        return vo;
    }

    /**
     * 补"提问数 / 已采纳数 / 采纳率"（M2-14）。
     *
     * <h2>为什么直接注入 PostMapper</h2>
     * 这里要的只是一条 COUNT 聚合。MyBatis 的 Mapper 是代理对象、不参与 Spring 的
     * bean 依赖图（它不反向依赖任何 Service），所以 user → content 的这个方向
     * 不会形成 bean 循环；相比再抽一层接口，少一个文件、少一层转调。
     *
     * <h2>为什么吞异常</h2>
     * 统计数字是主页的"锦上添花"，查询失败（比如慢查询超时）不该让整个主页 500 ——
     * 缺失时前端显示「—」。这与"计数取 Redis、失败回落 MySQL"的既有降级口径一致。
     */
    private void fillQuestionStats(UserVO vo, Long userId) {
        try {
            Map<String, Object> stats = postMapper.countQuestionStats(userId);
            int asked = ((Number) stats.getOrDefault("asked", 0)).intValue();
            int accepted = ((Number) stats.getOrDefault("accepted", 0)).intValue();

            vo.setAskedCount(asked);
            vo.setAcceptedCount(accepted);
            // 保留 4 位小数：3/4 → 0.75，2/3 → 0.6667，前端再格式化成百分比
            vo.setAcceptRate(asked == 0 ? null : Math.round(accepted * 10000.0 / asked) / 10000.0);
        } catch (Exception e) {
            log.warn("查询提问统计失败（不影响主页展示）| userId={} | err={}", userId, e.getMessage());
        }
    }

    private CurrentUserVO toCurrentVO(User user) {
        CurrentUserVO vo = new CurrentUserVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }
}
