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
 * ⚠️ 拉取方式的演变（M3-2）：原先这里写着"刻意不做轮询"，靠"启动时 + 打开通知中心时"
 * 两个时机拉取。现在改为：<b>正常走上游推送（SSE），SSE 连不上才降级为轮询</b>——
 * 轮询从"常态手段"变成"降级兜底"，因此原来的结论（不要常态轮询）依然成立。
 * 接线在 AppLayout，状态机在 composables/useNotificationStream.ts。
 */
export const useNotificationStore = defineStore('notification', () => {
  const unread = ref(0)

  /**
   * 服务端推送序号（只有"推送到达"会让它自增）。
   *
   * 通知中心的列表要"来新通知时自己刷新"，而它和角标不同：角标是一个数字，
   * 就地更新即可；列表是一组带分页/筛选的数据，必须重新拉。但触发条件**不能是
   * unread 本身的变化** —— 本地操作（点开一条 → decrement、全部已读 → reset）
   * 同样会改 unread，那些变化自己就是发起者，再触发一次列表刷新会变成
   * "用户点一下、列表整体重拉"的无谓抖动。
   * 于是把"推送到达"单独编码成一个单调递增的序号：只有 setUnread 让它 +1，
   * 消费方（NotificationView）只对该序号做防抖静默刷新。
   */
  const pushedAt = ref(0)

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

  /**
   * 直接写入未读数（SSE 推来的绝对数量）。
   *
   * 用"绝对值"而不是"增量"：推送值本身就是服务端 COUNT 的结果，
   * 增量在"本地乐观更新 + 推送"叠加时会算重复（比如标记已读同时本地 decrement
   * 又收到一条推送），绝对值天然幂等，重复收到同一条也不会出错。
   */
  function setUnread(count: number) {
    unread.value = Math.max(0, count)
    // 推送到达 = "别的会话产生了新事件"，列表页据此刷新（见 pushedAt 的说明）
    pushedAt.value += 1
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

  return { unread, pushedAt, refreshUnread, setUnread, reset, decrement, increment }
})
