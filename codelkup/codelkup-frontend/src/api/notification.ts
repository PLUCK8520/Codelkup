import { get, post } from '@/utils/request'
import type { NotificationVO, PageResult } from '@/types/api'

/**
 * 通知接口 —— 对应后端 NotificationController。
 *
 * ⚠️ 接口里没有"接收者 ID"参数：接收者一律从后端登录态取。
 * 这是有意的设计 —— 前端传 ID 就意味着可以查别人的通知，
 * 而"只能看自己的通知"应该在接口层面就不成立，而不是靠前端自觉。
 */
export const notificationApi = {
  /** 通知列表（按时间倒序） */
  list: (params: { onlyUnread?: boolean; page?: number; size?: number } = {}) =>
    get<PageResult<NotificationVO>>('/notifications', params),

  /** 未读数（顶栏角标轮询用） */
  unreadCount: () => get<number>('/notifications/unread-count'),

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
