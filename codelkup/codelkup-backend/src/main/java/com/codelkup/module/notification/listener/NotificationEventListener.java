package com.codelkup.module.notification.listener;

import com.codelkup.module.notification.event.NotificationEvent;
import com.codelkup.module.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 通知事件监听器 —— 把通知落库异步化。
 *
 * <h2>三个注解各自的作用</h2>
 * <ul>
 *   <li>{@code @Async} —— 落库不占用请求线程。用户点"关注"要立刻看到结果，
 *       不该等通知写完。</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} —— 主事务提交后才落库。
 *       理由与 S3 的计数监听器完全一致：若主事务回滚，"关注成功"这件事没发生，
 *       指向它的通知就是脏数据。</li>
 *   <li>{@code fallbackExecution = true} —— <b>没有活跃事务时也要执行</b>。
 *       这是个容易漏掉的开关：{@code AFTER_COMMIT} 的语义是"事务提交后"，
 *       如果发布事件时压根没有事务，监听器默认会被<b>静默跳过</b> ——
 *       表现为"关注成功了但收不到通知"，且没有任何报错。
 *       当前所有触发点都带 {@code @Transactional}，加这个开关是防御未来
 *       某个调用方忘了加事务注解的情况。</li>
 * </ul>
 *
 * <h2>异常处理</h2>
 * 与计数监听器同理：失败<b>不重抛</b>。异步线程里重抛没有接收方，
 * 只会丢上下文；而"通知没发出来"远比"关注因为通知失败而回滚"容易接受。
 *
 * @author 刘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotification(NotificationEvent event) {
        try {
            notificationService.create(event);
        } catch (Exception e) {
            // 不重抛 —— 通知失败绝不能让业务回滚
            log.warn("通知落库失败（不影响主流程）| receiverId={} | senderId={} | type={} | err={}",
                    event.receiverId(), event.senderId(), event.type(), e.getMessage());
        }
    }
}
