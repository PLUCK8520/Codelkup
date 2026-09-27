package com.codelkup.module.user.event;

/**
 * 用户会话被整体撤销事件（安全审计 F2）。
 *
 * <h2>为什么需要这个事件</h2>
 * 改密（以及将来任何"强制下线"动作）只做三件事是不够的：清 refresh 白名单、
 * 拉黑当前 accessToken、写用户级撤销水位 —— 它们管住了 HTTP 请求，
 * 却管不住<b>已经建立的 SSE 长连接</b>。SSE 在握手时用一次性 ticket 换取身份，
 * 之后不再校验凭证、也不会重新走过滤器：不主动关流，则被盗会话能继续"白拿"
 * 实时通知（未读数变化、通知内容），直到连接自然超时（30 分钟）或进程重启。
 *
 * <h2>为什么走事件而不是直接注入 SseService</h2>
 * 与 {@code ReputationEvent} 同理由：{@code UserService} 不该知道"通知模块里有个
 * SSE 注册表"。发布方只声明"这个人的会话被撤销了"这一领域事实，
 * 由通知模块的监听器决定"该关流"—— 若将来还要求"推一条'您的密码已修改'站内信"，
 * 也只加监听器，不动改密逻辑。
 *
 * @param userId 被撤销会话的用户
 * @author 刘
 */
public record UserSessionRevokedEvent(Long userId) {
}