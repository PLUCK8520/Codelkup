package com.codelkup.module.notification.listener;

import com.codelkup.module.notification.sse.SseService;
import com.codelkup.module.user.event.UserSessionRevokedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 会话撤销监听器 —— 凭证失效时立即关闭该用户的全部 SSE 连接（安全审计 F2）。
 *
 * <h2>为什么是同步 {@code @EventListener} 而不是 {@code AFTER_COMMIT} 异步</h2>
 * 与 {@code NotificationEventListener} 那条刻意相反，这里三个理由：
 * <ol>
 *   <li><b>与 Redis 撤销动作口径一致</b>：改密里的清白名单 / 拉黑 / 撤销水位
 *       都不受 Spring 事务管辖（Redis 操作即时生效、回滚不了）。若关流延迟到
 *       "提交后"，一旦事务回滚，就会留下"凭证已撤、流还开着"的不一致窗口。</li>
 *   <li><b>关流不能慢</b>：{@code complete()} 只是一次响应收尾，不查库、不阻塞，
 *       放在请求线程里无需异步化；异步反而引入"流还活着的十几毫秒"与线程池开销。</li>
 *   <li><b>不需要事务回调</b>：撤销是"立刻生效"的安全动作，不等落库。</li>
 * </ol>
 *
 * <h2>异常处理</h2>
 * {@code complete()} 对已损坏的连接可能抛异常 —— 捕获不重抛：关流是尽力而为的
 * 收尾动作，绝不能因为它失败而回滚"密码已修改"这件已经生效的事。
 *
 * @author 刘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionRevokedListener {

    private final SseService sseService;

    @EventListener
    public void onSessionRevoked(UserSessionRevokedEvent event) {
        try {
            sseService.closeAll(event.userId());
        } catch (Exception e) {
            log.warn("会话撤销后关闭 SSE 连接失败（不影响改密结果）| userId={} | err={}",
                    event.userId(), e.getMessage());
        }
    }
}