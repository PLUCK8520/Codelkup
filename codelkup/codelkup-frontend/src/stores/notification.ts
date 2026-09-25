import { defineStore } from 'pinia'
import { ref } from 'vue'
import { notificationApi } from '@/api/notification'

/**
 * 通知状态。
 *
 * 独立成 store 而不是放在 AppLayout 的局部 state 里，原因：
 * 未读数有两个使用点 —— 顶栏角标（AppLayout）和通知中心（NotificationView）。
 * 二者都需要在"读完通知"后立刻同步，放在组件里会退化成 props/emit 的层层透传，
 * 或者干脆各拉各的、数字不一致。
 *
 * ⚠️ 刻意<b>不做</b>轮询：没人会盯着浏览器等通知，定时请求纯属浪费。
 *    拉取时机只有两个 —— 应用启动（已登录时）和打开通知中心后。
 */
export const useNotificationStore = defineStore('notification', () => {
  const unread = ref(0)

  /**
   * 刷新未读数。
   *
   * 失败时静默归零而不弹错误：这是个"锦上添花"的角标，
   * 未登录、网络抖动都会让它失败，为此打扰用户是得不偿失的。
   */
  async function refreshUnread() {
    try {
      unread.value = await notificationApi.unreadCount()
    } catch {
      unread.value = 0
    }
  }

  /** 退出登录时清空，避免把上个账号的未读数带给下一个人 */
  function reset() {
    unread.value = 0
  }

  /** 本地减少未读数（标记已读后立即反馈，不再多一次请求） */
  function decrement(count = 1) {
    unread.value = Math.max(0, unread.value - count)
  }

  /** 本地回增（标记已读失败时回滚用） */
  function increment(count = 1) {
    unread.value += count
  }

  return { unread, refreshUnread, reset, decrement, increment }
})
