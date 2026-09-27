package com.codelkup.module.notification.sse;

import com.codelkup.common.constant.RedisKeys;
import com.codelkup.common.exception.BusinessException;
import com.codelkup.common.result.ErrorCode;
import com.codelkup.module.notification.mapper.NotificationMapper;
import com.codelkup.module.notification.vo.SseTicketVO;
import com.codelkup.security.TokenStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 实时通道（M3-1 / ADR-3、ADR-8）。
 *
 * <h2>职责</h2>
 * <ol>
 *   <li><b>一次性 ticket</b>：登录态换取 60 秒、单次消费的短期凭证，替代
 *       "token 进 URL"（{@code EventSource} 不能带 Header）。</li>
 *   <li><b>连接注册表</b>：{@code userId → 多个 SseEmitter}（一个人可能开多个标签页）。</li>
 *   <li><b>未读数推送</b>：通知落库 / 标记已读后，把该用户的最新未读数推给他所有连接。</li>
 *   <li><b>心跳</b>：定期发注释帧，穿透代理的 idle 超时并让"半开连接"尽早暴露。</li>
 * </ol>
 *
 * <h2>为什么查未读数直接注入 Mapper 而不注入 NotificationService</h2>
 * {@code NotificationService} 要在"通知落库后"回调本类推送（见其 create/markRead），
 * 若本类再反向注入 NotificationService，就会形成<b>构造器循环依赖</b>
 * （Spring 直接启动失败）。查未读数只需要一条 COUNT，注入 Mapper 既解开循环，
 * 也避免让"推送"依赖整个通知服务。
 *
 * <h2>单实例口径</h2>
 * 注册表是进程内 Map —— 多实例部署下"连在 A 实例的用户收不到 B 实例产生的通知"。
 * ALIGNMENT 已明确本轮只做单实例（扩展点是 Redis pub/sub），见 DEV-DOC 的说明。
 *
 * @author 刘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SseService {

    private final StringRedisTemplate stringRedisTemplate;
    private final NotificationMapper notificationMapper;

    /** 消费 ticket 时比对用户级撤销水位（第三轮审计：改密前签发的票不许连接） */
    private final TokenStore tokenStore;

    /** 指标注册表（M3-10）：当前 SSE 连接数 / 在线用户数 */
    private final MeterRegistry meterRegistry;

    /** ticket 有效期（秒） */
    @Value("${codelkup.sse.ticket-ttl-seconds:60}")
    private long ticketTtlSeconds;

    /** 连接超时（毫秒）—— 到点后 Spring 会关闭 emitter，前端负责重连 */
    @Value("${codelkup.sse.timeout-ms:1800000}")
    private long emitterTimeoutMs;

    /** 单用户最大并发连接数（多标签场景） */
    @Value("${codelkup.sse.max-connections-per-user:5}")
    private int maxConnectionsPerUser;

    /**
     * 用户 → 连接集合。
     *
     * <p>用 Set 而不是单个 emitter：同一个人开两个标签页就是两条独立连接，
     * 只保留最后一条会让另一个标签页的角标静默失效。集合本身是并发的，
     * 因为回调（完成/超时/出错）与心跳线程会同时改它。
     */
    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    // ==================== 一次性 ticket ====================

    /**
     * 签发 ticket（需登录态调用）。
     *
     * <p>值存 {@code userId|clientIp|签发时刻}，而不是存 token —— ticket 只解决"这一次连接是谁"，
     * 消费后立即作废（{@link #consumeTicket}）。<b>绑定签发端 IP</b>（安全审计
     * informational 项）：票虽然一次性且只有 60 秒，但若被泄漏（截图/日志/肩窥 URL），
     * 绑定后异地也消费不了；正常流程里签发与连接是同一个客户端，IP 必然一致。
     * 代价（如实记录）：NAT/移动网络在 60 秒内切 IP 会失败 —— 前端会重新换票重连，
     * 属于可接受的极小概率。末段的<b>签发时刻</b>用于消费时比对用户级撤销水位（第三轮审计）：
     * 改密前签发的票，改密后连不上。
     *
     * <p>Redis 不可用时<b>抛错</b>而不是"发一张假票"：假票在连接时必然验不过，
     * 前端会白跑一轮重连；直接报错让前端立刻走轮询兜底（那正是设计好的降级路径）。
     */
    public SseTicketVO issueTicket(Long userId, String clientIp) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        // 值 = userId|clientIp|签发时刻(epoch 秒)。
        // 末段的签发时刻专为"改密即断流"补洞（第三轮审计）：票有效期 60 秒，
        // 若在被盗 access 手里**改密之前**签发、改密之后才消费，那时 closeAll 已经跑完
        // （连接还不存在）—— 只有把签发时刻与用户级撤销水位比对，才能把这类票拒掉。
        String value = userId + "|" + (clientIp == null ? "?" : clientIp)
                + "|" + Instant.now().getEpochSecond();
        try {
            stringRedisTemplate.opsForValue()
                    .set(RedisKeys.SSE_TICKET + ticket, value, ticketTtlSeconds,
                            java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("签发 SSE ticket 失败（Redis 不可用，前端将回退轮询）| userId={} | err={}", userId, e.getMessage());
            throw new BusinessException(ErrorCode.CACHE_ERROR, "实时通道暂不可用，请稍后再试");
        }
        log.debug("签发 SSE ticket | userId={} | ttl={}s", userId, ticketTtlSeconds);
        return new SseTicketVO(ticket, ticketTtlSeconds);
    }

    /**
     * 消费 ticket：读取即删除（GETDEL），天然"一次性"。
     *
     * <p>用 GETDEL 而不是"先 GET 再 DEL"：后者在两次命令之间有窗口，
     * 并发连接可能用同一张票建立两条流（票的单次语义必须由一次原子操作保证）。
     *
     * <p>消费时校验 <b>签发端 IP == 消费端 IP</b>：不符则返回 null（票已按"一次性"
     * 语义消费掉，不给"换个 IP 再试"的机会 —— 与"失败也消费"的既有口径一致）。
     *
     * @return 有效且 IP 匹配则返回 userId；无效 / 已消费 / 已过期 / IP 不符 / Redis 异常一律 null
     */
    public Long consumeTicket(String ticket, String clientIp) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        try {
            String value = stringRedisTemplate.opsForValue().getAndDelete(RedisKeys.SSE_TICKET + ticket);
            if (value == null) {
                return null;
            }
            int sep = value.indexOf('|');
            if (sep < 0) {
                // 兼容旧格式（纯 userId）：没有绑定信息的老票直接按旧语义放行
                return Long.valueOf(value);
            }
            Long userId = Long.valueOf(value.substring(0, sep));
            String rest = value.substring(sep + 1);
            int sep2 = rest.indexOf('|');
            String boundIp = sep2 < 0 ? rest : rest.substring(0, sep2);
            String actualIp = clientIp == null ? "?" : clientIp;
            if (!boundIp.equals(actualIp)) {
                log.warn("SSE ticket 消费端 IP 不符，拒绝 | userId={} | bound={} | actual={}",
                        userId, boundIp, actualIp);
                return null;
            }
            // 用户级撤销水位（第三轮审计）：票在被撤销之前签发的，一律拒 ——
            // 与 JwtAuthenticationFilter 对 access 的判定同一口径（严格小于才拒）。
            if (sep2 >= 0) {
                long issued = Long.parseLong(rest.substring(sep2 + 1));
                Long watermark = tokenStore.revokedBefore(userId);
                if (watermark != null && issued < watermark) {
                    log.warn("SSE ticket 签发于会话撤销之前，拒绝 | userId={} | issued={} | revokedBefore={}",
                            userId, issued, watermark);
                    return null;
                }
            }
            return userId;
        } catch (Exception e) {
            log.warn("消费 SSE ticket 失败 | err={}", e.getMessage());
            return null;
        }
    }

    // ==================== 连接 ====================

    /**
     * 建立一条 SSE 连接。
     *
     * <p>连上后<b>立刻推一次当前未读数</b>：否则刚连上的客户端要等到下一次通知
     * 才会校正角标，而中间这段空白正是"用户刷新页面后角标短暂不准"的来源。
     */
    public SseEmitter connect(Long userId) {
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        register(userId, emitter);

        emitter.onCompletion(() -> remove(userId, emitter, "完成"));
        emitter.onTimeout(() -> remove(userId, emitter, "超时"));
        emitter.onError(e -> remove(userId, emitter, "出错"));

        log.debug("SSE 连接建立 | userId={} | 当前连接数={}", userId, countConnections(userId));
        sendUnreadCount(userId, emitter);
        return emitter;
    }

    /**
     * 注册指标（M3-10）：当前连接数与在线用户数。
     *
     * <p>为什么这两块必须做成指标而不是只写日志：连接泄漏（前端异常退出没关流、
     * 代理悄悄断开而本端没感知）唯一的早期信号就是"连接数只涨不跌"，
     * 而 DEBUG 日志在生产是关掉的。Gauge 让 Prometheus 每 15 秒拿到一次当前值，
     * 趋势即可见。
     *
     * <p>⚠️ 与热度分那处同理：Gauge 必须持有<b>稳定引用</b>并只注册一次。
     * 这里第一个 Gauge 用 {@code this} 方法引用（本 Bean 是单例，引用稳定）；
     * 第二个直接观测 {@code emitters} 这个 Map 字段（不复制、不重建），
     * 因为 Map 的 size 会随连接建立/移除实时变化。
     */
    @PostConstruct
    void registerMetrics() {
        Gauge.builder("codelkup.sse.connections", this, SseService::totalConnections)
                .description("当前 SSE 连接数（同一用户多标签会计数多次）")
                .register(meterRegistry);
        Gauge.builder("codelkup.sse.online_users", emitters, Map::size)
                .description("当前持有至少一条 SSE 连接的用户数")
                .register(meterRegistry);
    }

    /** 已建立的连接总数（心跳日志与 M3-10 的指标都用它） */
    public int totalConnections() {
        return emitters.values().stream().mapToInt(Set::size).sum();
    }

    private int countConnections(Long userId) {
        Set<SseEmitter> set = emitters.get(userId);
        return set == null ? 0 : set.size();
    }

    private void register(Long userId, SseEmitter emitter) {
        Set<SseEmitter> set = emitters.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());

        // 超出上限时关掉其中一个旧连接：多标签场景下"保证新的这个能用"
        // 比"精确淘汰最老的那个"更重要（集合本身不保序，也不值得为它换成有序结构）
        if (set.size() >= maxConnectionsPerUser) {
            SseEmitter victim = set.iterator().next();
            set.remove(victim);
            try {
                victim.complete();
            } catch (Exception ignored) {
                // 已经坏掉的连接，忽略
            }
            log.info("SSE 连接数达上限（{}），关闭一个旧连接 | userId={}", maxConnectionsPerUser, userId);
        }
        set.add(emitter);
    }

    private void remove(Long userId, SseEmitter emitter, String reason) {
        Set<SseEmitter> set = emitters.get(userId);
        if (set != null && set.remove(emitter)) {
            log.debug("SSE 连接移除（{}）| userId={} | 剩余连接数={}", reason, userId, set.size());
            if (set.isEmpty()) {
                emitters.remove(userId, set);
            }
        }
    }

    /**
     * 关闭某用户的全部 SSE 连接（安全审计 F2）。
     *
     * <p>由 {@code SessionRevokedListener} 在"会话被整体撤销"（如改密）后调用。
     * <b>为什么必须关流</b>：SSE 只在握手时用一次性 ticket 确认身份，连上之后
     * 不再过过滤器、不再看凭证 —— 凭据撤销管不到这条活着的长连接。不关流，
     * 被盗会话能继续接收实时通知，直到连接自然超时或进程重启。
     *
     * <p>顺序刻意是"先摘注册表、再逐个关"：
     * <ul>
     *   <li>先 {@code remove} 取得快照，转瞬建立的新连接会落到<b>新的集合</b>里，
     *       不会被这次撤销误杀；</li>
     *   <li>{@code complete()} 会触发 onCompletion 回调再次调 {@code remove}，
     *       彼时注册表里已没有该用户，天然幂等、不会把别人的连接连带删掉。</li>
     * </ul>
     *
     * <p>前端表现为 EventSource 断开后自动重连 → 换票时因凭证已失效返回 40101/
     * 40103 → 走既有的"刷新失败即登出"路径。这正是一次"强制下线"应有的行为。
     */
    public void closeAll(Long userId) {
        Set<SseEmitter> set = emitters.remove(userId);
        if (set == null || set.isEmpty()) {
            return;
        }
        int closed = 0;
        for (SseEmitter emitter : List.copyOf(set)) {
            try {
                emitter.complete();
                closed++;
            } catch (Exception e) {
                // 已经坏掉的连接（超时/出错回调抢先收尾），忽略即可
                log.debug("SSE 关流失败（连接可能已由容器收尾）| userId={} | err={}", userId, e.getMessage());
            }
        }
        log.info("会话撤销，关闭全部 SSE 连接 | userId={} | 关闭={} 条", userId, closed);
    }

    // ==================== 推送 ====================

    /**
     * 把指定用户的最新未读数推给他所有连接。
     *
     * <p>由 {@code NotificationService} 在"通知落库 / 标记已读"之后调用。
     * 没有任何连接时是一次空遍历（不查库）—— 轮询兜底的那部分用户不该为 SSE 付成本。
     */
    public void notifyUnread(Long userId) {
        Set<SseEmitter> set = emitters.get(userId);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : set) {
            sendUnreadCount(userId, emitter);
        }
    }

    private void sendUnreadCount(Long userId, SseEmitter emitter) {
        int count = notificationMapper.countUnread(userId);
        try {
            // 事件名与载荷是与前端的契约：`event: unread` + `data: {"count": n}`。
            // 只推"未读数"而不是整条通知：前端拿到数字后按需刷新列表，
            // 避免把通知内容复制一份塞进流里（那会多一套权限与转义的责任）。
            emitter.send(SseEmitter.event().name("unread").data(Map.of("count", count)));
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开（写失败）—— 摘掉即可，等 onError/onCompletion 收尾重复摘除也是幂等的
            log.debug("SSE 推送失败，移除连接 | userId={} | err={}", userId, e.getMessage());
            remove(userId, emitter, "推送失败");
        }
    }

    /**
     * 心跳：发注释帧（{@code :ping}）。
     *
     * <p>为什么用注释帧而不是自定义事件：注释行按 SSE 规范会被浏览器忽略，
     * 不会触发前端任何 handler，纯粹用来"保持连接活着" —— 用真实事件会造成
     * 前端每 25 秒醒一次做无意义的处理。
     *
     * <p>同时把连接数打到 DEBUG 日志：连接泄漏（前端没正确关闭）只有靠这个数字才看得出来。
     */
    @Scheduled(fixedDelayString = "${codelkup.sse.heartbeat-ms:25000}")
    public void heartbeat() {
        int total = totalConnections();
        if (total == 0) {
            return;
        }
        log.debug("SSE 心跳 | 连接数={} | 在线用户数={}", total, emitters.size());
        for (Map.Entry<Long, Set<SseEmitter>> entry : emitters.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    log.debug("SSE 心跳写失败，移除连接 | userId={} | err={}", entry.getKey(), e.getMessage());
                    remove(entry.getKey(), emitter, "心跳失败");
                }
            }
        }
    }
}