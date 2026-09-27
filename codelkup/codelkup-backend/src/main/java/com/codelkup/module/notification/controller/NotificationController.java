package com.codelkup.module.notification.controller;

import com.codelkup.common.result.PageResult;
import com.codelkup.common.result.Result;
import com.codelkup.module.notification.service.NotificationService;
import com.codelkup.module.notification.vo.NotificationTypeUnreadVO;
import com.codelkup.module.notification.vo.NotificationVO;
import com.codelkup.module.user.entity.User;
import com.codelkup.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站内通知接口（Sprint 4）。
 *
 * <p>全部接口都需要登录 —— 通知天然属于"我的"数据，
 * 且接收者 ID 一律从登录态取（{@code @CurrentUser}），
 * 绝不接受请求参数传入。这样"查别人的通知"在接口层面就不存在。
 *
 * @author 刘
 */
@Slf4j
@Validated
@Tag(name = "通知", description = "站内通知与未读数")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "通知列表",
            description = "按时间倒序；onlyUnread=true 只看未读；"
                    + "type 按类型分组筛选（可重复或逗号分隔，如 type=1,2），省略即全部类型")
    @GetMapping
    public Result<PageResult<NotificationVO>> list(
            @CurrentUser(required = true) Long userId,
            @RequestParam(defaultValue = "false") boolean onlyUnread,
            @RequestParam(required = false) List<Integer> type,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码最小为 1") Integer page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页最少 1 条")
            @Max(value = 50, message = "每页最多 50 条") Integer size) {
        return Result.success(notificationService.list(userId, onlyUnread, type, page, size));
    }

    @Operation(summary = "各类型未读数",
            description = "通知中心的分组角标。只返回有未读的类型（缺的按 0 处理）")
    @GetMapping("/unread-counts")
    public Result<List<NotificationTypeUnreadVO>> unreadCounts(@CurrentUser(required = true) Long userId) {
        return Result.success(notificationService.unreadCounts(userId));
    }

    @Operation(summary = "未读数", description = "顶栏红点角标")
    @GetMapping("/unread-count")
    public Result<Integer> unreadCount(@CurrentUser(required = true) Long userId) {
        return Result.success(notificationService.unreadCount(userId));
    }

    @Operation(summary = "标记单条已读",
            description = "只能标记自己的通知；标记他人的不会报错但也不会生效（受影响行数为 0）")
    @PostMapping("/{id}/read")
    public Result<Boolean> markRead(@PathVariable Long id,
                                    @CurrentUser(required = true) Long userId) {
        return Result.success(notificationService.markRead(id, userId));
    }

    @Operation(summary = "全部标记已读", description = "返回本次影响的通知条数")
    @PostMapping("/read-all")
    public Result<Integer> markAllRead(@CurrentUser(required = true) Long userId) {
        return Result.success(notificationService.markAllRead(userId));
    }
}
