package com.codelkup.module.social.controller;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.PageResult;
import com.codelkup.common.result.Result;
import com.codelkup.module.social.service.FollowService;
import com.codelkup.module.social.vo.FollowStateVO;
import com.codelkup.module.social.vo.FollowUserVO;
import com.codelkup.module.user.entity.User;
import com.codelkup.security.CurrentUser;
import com.codelkup.security.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 关注关系接口（Sprint 4）。
 *
 * <p>路径挂在 {@code /api/users/{id}/...} 下而不是另起 {@code /api/follows}：
 * 关注关系是"用户的从属资源"，REST 语义上本来就该嵌在用户路径里。
 * 好处是 SecurityConfig 能继续用 {@code /api/users/*} 这一条规则覆盖公开读接口。
 *
 * @author 刘
 */
@Slf4j
@Validated
@Tag(name = "关注", description = "关注 / 粉丝 / 关注列表")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;
    private final RateLimitService rateLimitService;

    /** 关注/取关限流：默认 60 次/小时/用户（L-4 补齐 —— 循环关注是通知骚扰的放大器） */
    @Value("${codelkup.rate-limit.follow-per-hour:60}")
    private int followPerHour;

    /** 关注与取关共用同一个桶：两者是对同一关系的双向操作，分开计等于额度翻倍 */
    private void requireFollowQuota(Long userId) {
        if (!rateLimitService.tryAcquire("follow", "u:" + userId, followPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    @Operation(summary = "关注用户", description = "幂等：重复关注返回成功态，不报错；不能关注自己。按用户限流（默认 60 次/小时）")
    @PostMapping("/{id}/follow")
    public Result<FollowStateVO> follow(@PathVariable Long id,
                                        @CurrentUser(required = true) Long userId) {
        requireFollowQuota(userId);
        return Result.success(followService.follow(id, userId));
    }

    @Operation(summary = "取消关注", description = "幂等：未关注时直接返回，不报错")
    @DeleteMapping("/{id}/follow")
    public Result<FollowStateVO> unfollow(@PathVariable Long id,
                                          @CurrentUser(required = true) Long userId) {
        requireFollowQuota(userId);
        return Result.success(followService.unfollow(id, userId));
    }

    @Operation(summary = "关注状态与计数",
            description = "following：未登录为 null；followerCount/followeeCount 为公开计数")
    @GetMapping("/{id}/follow-state")
    public Result<FollowStateVO> state(@PathVariable Long id,
                                       @CurrentUser User currentUser) {
        Long viewerId = currentUser == null ? null : currentUser.getId();
        return Result.success(followService.state(id, viewerId));
    }

    @Operation(summary = "粉丝列表", description = "谁关注了 TA，按关注时间倒序")
    @GetMapping("/{id}/followers")
    public Result<PageResult<FollowUserVO>> followers(
            @PathVariable Long id,
            @CurrentUser User currentUser,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码最小为 1") Integer page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页最少 1 条")
            @Max(value = 50, message = "每页最多 50 条") Integer size) {
        Long viewerId = currentUser == null ? null : currentUser.getId();
        return Result.success(followService.followers(id, viewerId, page, size));
    }

    @Operation(summary = "关注列表", description = "TA 关注了谁，按关注时间倒序")
    @GetMapping("/{id}/followees")
    public Result<PageResult<FollowUserVO>> followees(
            @PathVariable Long id,
            @CurrentUser User currentUser,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码最小为 1") Integer page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页最少 1 条")
            @Max(value = 50, message = "每页最多 50 条") Integer size) {
        Long viewerId = currentUser == null ? null : currentUser.getId();
        return Result.success(followService.followees(id, viewerId, page, size));
    }
}
