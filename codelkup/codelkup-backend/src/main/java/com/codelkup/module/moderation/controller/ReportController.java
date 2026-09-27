package com.codelkup.module.moderation.controller;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.PageResult;
import com.codelkup.common.result.Result;
import com.codelkup.module.moderation.dto.ReportCreateRequest;
import com.codelkup.module.moderation.dto.ReportHandleRequest;
import com.codelkup.module.moderation.service.ReportService;
import com.codelkup.module.moderation.vo.ReportAdminVO;
import com.codelkup.module.moderation.vo.ReportCreateVO;
import com.codelkup.module.moderation.vo.ReportHandleVO;
import com.codelkup.security.CurrentUser;
import com.codelkup.security.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 举报与审核接口。
 *
 * <p>两条完全不同的权限线，所以放在同一个控制器里也仍然好读：
 * <ul>
 *   <li>{@code POST /api/reports} —— 任何登录用户（游客看不到举报入口，故必须登录）</li>
 *   <li>{@code /api/admin/**} —— {@code @PreAuthorize("hasRole('ADMIN')")}，普通用户拿到 40301</li>
 * </ul>
 * 路径上就用 {@code /api/admin} 前缀而不是自造一个 {@code /api/moderation}：
 * 现有的计数重建接口（InteractionController）已经在用这个前缀，
 * 管理端能力集中在同一命名空间下，日后要按前缀做统一审计/限流也只需改一处。
 *
 * @author 刘
 */
@Slf4j
@Validated
@Tag(name = "举报与审核", description = "用户举报 / 管理员审核队列")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final RateLimitService rateLimitService;

    /** 举报限流：默认 20 次/小时/用户 */
    @Value("${codelkup.rate-limit.report-per-hour:20}")
    private int reportPerHour;

    // ==================== 用户侧 ====================

    @Operation(summary = "提交举报", description = "需登录；按用户限流（默认 20 次/小时）。同一目标重复举报返回 40909（幂等）")
    @PostMapping("/reports")
    public Result<ReportCreateVO> createReport(@Valid @RequestBody ReportCreateRequest body,
                                               @CurrentUser(required = true) Long userId) {
        // 与内容创作同口径：举报必然登录，按用户计比按 IP 精确
        // （按 IP 会让同一 NAT 下的多个用户互相误伤）
        if (!rateLimitService.tryAcquire("report", "u:" + userId, reportPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return Result.success(reportService.create(body, userId));
    }

    // ==================== 管理侧 ====================

    @Operation(summary = "审核队列", description = "仅管理员；按举报时间倒序，可按状态过滤（0待处理 1已驳回 2已删除内容）")
    @GetMapping("/admin/reports")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<ReportAdminVO>> listReports(
            @RequestParam(required = false)
            @Min(value = 0, message = "状态最小为 0")
            @Max(value = 2, message = "状态最大为 2") Integer status,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码最小为 1") Integer page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页最少 1 条")
            @Max(value = 50, message = "每页最多 50 条") Integer size) {
        return Result.success(reportService.listAdmin(status, page, size));
    }

    @Operation(summary = "待处理举报数", description = "仅管理员；管理端角标用")
    @GetMapping("/admin/reports/pending-count")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> pendingCount() {
        return Result.success(reportService.pendingCount());
    }

    @Operation(summary = "处理举报", description = "仅管理员；action=dismiss 驳回 / delete_target 删除内容；已处理的举报返回 40910")
    @PostMapping("/admin/reports/{id}/handle")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<ReportHandleVO> handleReport(@PathVariable Long id,
                                               @Valid @RequestBody ReportHandleRequest body,
                                               @CurrentUser(required = true) Long adminId) {
        return Result.success(reportService.handle(id, body, adminId));
    }
}