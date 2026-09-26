package com.codelkup.module.interaction.controller;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.Result;
import com.codelkup.module.interaction.dto.CommentCreateRequest;
import com.codelkup.module.interaction.service.CommentService;
import com.codelkup.module.interaction.service.CounterRebuildService;
import com.codelkup.module.interaction.service.UserActionService;
import com.codelkup.module.interaction.vo.ActionResultVO;
import com.codelkup.module.interaction.vo.CommentVO;
import com.codelkup.module.interaction.vo.RebuildReportVO;
import com.codelkup.module.user.entity.User;
import com.codelkup.security.CurrentUser;
import com.codelkup.security.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 互动接口：评论 / 点赞 / 收藏 / 计数重建。
 *
 * <p>权限划分：
 * <ul>
 *   <li>读评论 —— 游客可读（与帖子列表一致）</li>
 *   <li>写互动 —— {@code @CurrentUser(required = true)} 强制登录</li>
 *   <li>计数重建 —— 仅管理员（自助式运维端点）</li>
 * </ul>
 *
 * @author 刘
 */
@Slf4j
@Tag(name = "互动", description = "评论 / 点赞 / 收藏 / 计数重建")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InteractionController {

    private final CommentService commentService;
    private final UserActionService userActionService;
    private final CounterRebuildService counterRebuildService;
    private final RateLimitService rateLimitService;

    /** 评论限流：默认 60 条/小时/用户 */
    @Value("${codelkup.rate-limit.comment-per-hour:60}")
    private int commentPerHour;

    // ==================== 评论 ====================

    @Operation(summary = "评论列表", description = "树形返回（只做两级）；计数取 Redis 实时值")
    @GetMapping("/posts/{postId}/comments")
    public Result<List<CommentVO>> listComments(@PathVariable Long postId,
                                                @CurrentUser User currentUser) {
        Long viewerId = currentUser == null ? null : currentUser.getId();
        return Result.success(commentService.listByPost(postId, viewerId, isAdmin(currentUser)));
    }

    @Operation(summary = "发表评论 / 回复", description = "parentId 传被回复的评论ID 即为回复。按用户限流（默认 60 条/小时）")
    @PostMapping("/posts/{postId}/comments")
    public Result<CommentVO> createComment(@PathVariable Long postId,
                                           @Valid @RequestBody CommentCreateRequest body,
                                           @CurrentUser(required = true) Long userId) {
        // 与发帖同一口径：内容创作按**用户**限流（创作必然登录，按 IP 会误伤 NAT 后的他人）
        if (!rateLimitService.tryAcquire("comment", "u:" + userId, commentPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return Result.success(commentService.create(postId, body, userId));
    }

    @Operation(summary = "删除评论", description = "逻辑删除；仅作者本人或管理员")
    @DeleteMapping("/comments/{commentId}")
    public Result<Void> deleteComment(@PathVariable Long commentId,
                                      @CurrentUser(required = true) User currentUser) {
        commentService.delete(commentId, currentUser.getId(), isAdmin(currentUser));
        return Result.success();
    }

    @Operation(summary = "采纳评论", description = "仅楼主，仅提问帖")
    @PostMapping("/posts/{postId}/accept/{commentId}")
    public Result<Void> acceptComment(@PathVariable Long postId,
                                      @PathVariable Long commentId,
                                      @CurrentUser(required = true) Long userId) {
        commentService.accept(postId, commentId, userId);
        return Result.success();
    }

    // ==================== 帖子点赞 / 收藏 ====================

    @Operation(summary = "点赞帖子", description = "幂等：重复点赞返回成功态且计数不变")
    @PostMapping("/posts/{id}/like")
    public Result<ActionResultVO> likePost(@PathVariable Long id,
                                           @CurrentUser(required = true) Long userId) {
        return Result.success(userActionService.likePost(id, userId));
    }

    @Operation(summary = "取消点赞", description = "幂等：未点赞时直接返回成功态")
    @DeleteMapping("/posts/{id}/like")
    public Result<ActionResultVO> unlikePost(@PathVariable Long id,
                                             @CurrentUser(required = true) Long userId) {
        return Result.success(userActionService.unlikePost(id, userId));
    }

    @Operation(summary = "收藏帖子", description = "幂等：重复收藏返回成功态且计数不变")
    @PostMapping("/posts/{id}/collect")
    public Result<ActionResultVO> collectPost(@PathVariable Long id,
                                              @CurrentUser(required = true) Long userId) {
        return Result.success(userActionService.collectPost(id, userId));
    }

    @Operation(summary = "取消收藏", description = "幂等：未收藏时直接返回成功态")
    @DeleteMapping("/posts/{id}/collect")
    public Result<ActionResultVO> uncollectPost(@PathVariable Long id,
                                                @CurrentUser(required = true) Long userId) {
        return Result.success(userActionService.uncollectPost(id, userId));
    }

    // ==================== 评论点赞 ====================

    @Operation(summary = "点赞评论", description = "幂等")
    @PostMapping("/comments/{commentId}/like")
    public Result<ActionResultVO> likeComment(@PathVariable Long commentId,
                                              @CurrentUser(required = true) Long userId) {
        return Result.success(userActionService.likeComment(commentId, userId));
    }

    @Operation(summary = "取消评论点赞", description = "幂等")
    @DeleteMapping("/comments/{commentId}/like")
    public Result<ActionResultVO> unlikeComment(@PathVariable Long commentId,
                                                @CurrentUser(required = true) Long userId) {
        return Result.success(userActionService.unlikeComment(commentId, userId));
    }

    // ==================== 计数重建（管理员运维端点） ====================

    /**
     * 重建单帖计数。
     *
     * <p>做成 HTTP 端点而不是"只能跑命令行"，是因为故障恢复时
     * 希望能在不登机器、不改配置的前提下立即修正数据。
     * 权限收紧到 ADMIN —— 它是个破坏性写操作（会覆盖计数），
     * 不能让普通用户触发。
     */
    @Operation(summary = "重建单帖计数", description = "从 t_user_action / t_comment 反查真值，覆盖 Redis 与 MySQL。仅管理员")
    @PostMapping("/admin/counters/rebuild/{postId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<RebuildReportVO> rebuildOne(@PathVariable Long postId) {
        return Result.success(counterRebuildService.rebuildPost(postId));
    }

    @Operation(summary = "全量重建计数", description = "扫描全部帖子并校准。仅管理员")
    @PostMapping("/admin/counters/rebuild")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<RebuildReportVO> rebuildAll() {
        return Result.success(counterRebuildService.rebuildAll());
    }

    // ==================== 私有工具 ====================

    /** role == 2 即管理员 */
    private boolean isAdmin(User user) {
        return user != null && user.getRole() != null && user.getRole() == 2;
    }
}
