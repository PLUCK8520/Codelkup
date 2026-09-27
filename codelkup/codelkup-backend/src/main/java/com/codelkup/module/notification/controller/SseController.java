package com.codelkup.module.notification.controller;

import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.common.result.Result;
import com.codelkup.module.notification.sse.SseService;
import com.codelkup.module.notification.vo.SseTicketVO;
import com.codelkup.security.CurrentUser;
import com.codelkup.security.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 实时通道：SSE 通知流 + 一次性 ticket（M3-1）。
 *
 * <h2>为什么这两个端点放在通知模块，而不是各自的"路径归属"模块</h2>
 * <ul>
 *   <li>{@code POST /api/auth/sse-ticket} 按路径像认证接口，但签发票据要用到
 *       {@link SseService}（通知模块）。若把它写进 {@code AuthController}（user 模块），
 *       就产生 {@code user → notification} 依赖，而通知模块已经依赖 user（查昵称），
 *       包依赖会成环。这与 M2-8 把 {@code /api/users/me/collections} 放在内容控制器
 *       是同一条判据：<b>成环的代价大于"路径与包名不整齐"的代价</b>。</li>
 *   <li>{@code GET /api/notifications/stream} 本来就归通知模块，只是它的鉴权方式
 *       与其它接口不同（见下）。</li>
 * </ul>
 *
 * <h2>为什么流的鉴权走 ticket 而不是 Authorization 头</h2>
 * 浏览器 {@code EventSource} <b>不能</b>自定义请求头，所以 accessToken 既进不了
 * Header，也不能进 query（会落进浏览器历史、nginx 访问日志 —— ADR-8 的结论）。
 * 折中方案是一次性 ticket：登录态签发 → 60 秒内单次消费 → 连接期间不再需要凭证。
 *
 * <h2>本接口不套 Result 封装</h2>
 * {@code /stream} 返回的是 {@code text/event-stream} 长连接，不是一次性 JSON。
 * 失败（ticket 无效）仍按全局约定返回 {@code 200 + 40101} JSON —— 但
 * {@code EventSource} 解析不了 JSON，前端只会看到 {@code onerror}。
 * 因此前端不必去区分失败原因，统一按"实时通道不可用 → 退轮询"处理（M3-2）。
 *
 * @author 刘
 */
@Slf4j
@Validated
@Tag(name = "实时通道", description = "SSE 通知流与一次性 ticket")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SseController {

    private final SseService sseService;
    private final RateLimitService rateLimitService;

    /** ticket 签发限流：默认 120 次/小时/用户（正常一次连接换一张，重连也远低于此） */
    @Value("${codelkup.rate-limit.sse-ticket-per-hour:120}")
    private int sseTicketPerHour;

    @Operation(summary = "换取 SSE ticket",
            description = "需登录；返回 60 秒内有效、只能消费一次的 ticket（绑定签发端 IP）。按用户限流（默认 120 次/小时）")
    @PostMapping("/auth/sse-ticket")
    public Result<SseTicketVO> issueTicket(@CurrentUser(required = true) Long userId,
                                           HttpServletRequest request) {
        if (!rateLimitService.tryAcquire("sse_ticket", "u:" + userId, sseTicketPerHour, 60)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "连接过于频繁，请稍后再试");
        }
        // 绑定签发端 IP（安全审计 informational）：正常流程签发与连接是同一客户端，
        // 票即使泄漏、异地也消费不了（详见 SseService.issueTicket 的说明）
        return Result.success(sseService.issueTicket(userId, request.getRemoteAddr()));
    }

    /**
     * SSE 通知流。
     *
     * <p>连上后立刻会收到一次 {@code event: unread}（当前未读数），此后每次未读数变化
     * 再推一次；期间每 25 秒一条注释帧 {@code :ping} 维持连接。
     *
     * <p>⚠️ 失败时 ticket 已被消费（GETDEL）—— 这是刻意的：与其让一张用过的票
     * "再试试看"，不如让前端重新换一张，语义清楚且不会留下可复用的凭证。
     */
    @Operation(summary = "SSE 通知流",
            description = "用一次性 ticket 鉴权（EventSource 无法带 Header）；事件：unread {count}；心跳为注释帧")
    @GetMapping(value = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam("ticket") @NotBlank(message = "缺少 ticket")
            @Size(max = 64, message = "ticket 格式不正确") String ticket,
            HttpServletRequest request,
            HttpServletResponse response) {
        Long userId = sseService.consumeTicket(ticket, request.getRemoteAddr());
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "ticket 无效或已被使用，请重新获取");
        }
        // 明确告诉反向代理不要缓冲这条响应：否则 nginx 默认会攒够一个 buffer 才转发，
        // 表现为"事件延迟数十秒才到"。nginx.conf 里也有同名开关（M3-2），这里是兜底 ——
        // 换任何支持该头部的代理都不必再改配置。
        response.setHeader("X-Accel-Buffering", "no");
        return sseService.connect(userId);
    }
}