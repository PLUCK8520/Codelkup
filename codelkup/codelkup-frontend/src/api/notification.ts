import { get, post } from '@/utils/request'
import type { NotificationTypeUnreadVO, NotificationVO, PageResult, SseTicketVO } from '@/types/api'

/**
 * 通知接口 —— 对应后端 NotificationController。
 *
 * ⚠️ 接口里没有"接收者 ID"参数：接收者一律从后端登录态取。
 * 这是有意的设计 —— 前端传 ID 就意味着可以查别人的通知，
 * 而"只能看自己的通知"应该在接口层面就不成立，而不是靠前端自觉。
 */
export const notificationApi = {
  /**
   * 通知列表（按时间倒序）。
   *
   * ⚠️ `type` 在**这里**拼成逗号串再发出，不做成数组参数直接交给 axios：
   *    axios 的默认数组序列化是 `type[]=1&type[]=2`，而后端的
   *    `@RequestParam List<Integer> type` 只认 `type=1&type=2`（重复键）与
   *    `type=1,2`（逗号串）—— **方括号形式会被静默忽略**（参数名不匹配 →
   *    后端当成没传 → 返回全部类型），表现为"点了 tab 没反应"且毫无报错。
   *    实测过三种写法：逗号 ✓、重复键 ✓、方括号 ✗（返回全量）。
   */
  list: (params: { onlyUnread?: boolean; type?: number[]; page?: number; size?: number } = {}) => {
    const { type, ...rest } = params
    const query = type && type.length ? { ...rest, type: type.join(',') } : rest
    return get<PageResult<NotificationVO>>('/notifications', query)
  },

  /** 未读数（顶栏角标；SSE 降级为轮询时定期调用） */
  unreadCount: () => get<number>('/notifications/unread-count'),

  /**
   * 换取 SSE 一次性 ticket（M3-2）。
   *
   * ⚠️ 路径是 `/auth/sse-ticket`（认证命名空间）而不是 `/notifications/...`：
   *    票据是"登录态 → 短期凭证"的兑换。后端把它实现在通知模块的 SseController
   *    （避免 user ↔ notification 包依赖成环，详见 DEV-DOC 的实时通道一节），
   *    但对外路径仍按语义归属放在 auth 下。
   */
  sseTicket: () => post<SseTicketVO>('/auth/sse-ticket'),

  /**
   * 各类型未读数 —— 通知中心的分组角标。
   * 只返回有未读的类型，缺的按 0 处理。
   */
  unreadCounts: () => get<NotificationTypeUnreadVO[]>('/notifications/unread-counts'),

  /**
   * 标记单条已读。
   *
   * 返回 boolean 表示是否真的改动了一行 —— 标记别人的通知不会报错，
   * 但会返回 false（后端靠 `receiver_id` 条件挡住了水平越权）。
   */
  markRead: (id: number) => post<boolean>(`/notifications/${id}/read`),

  /** 全部标记已读，返回受影响条数 */
  markAllRead: () => post<number>('/notifications/read-all'),
}
