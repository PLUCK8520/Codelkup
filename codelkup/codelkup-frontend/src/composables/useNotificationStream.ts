/**
 * 通知实时通道（M3-2）。
 *
 * <h2>它做什么</h2>
 * 登录后建立一条 SSE 长连接（`/api/notifications/stream`），把服务端推来的
 * 未读数写进 store —— 顶栏角标从此是"实时的"，而不是"打开页面那一刻的快照"。
 *
 * <h2>三条关键行为</h2>
 * <ol>
 *   <li><b>每次连接都换一张新 ticket</b>：ticket 是一次性的（GETDEL），
 *       断线后必须重新签发再连 —— 不能拿旧 URL 重连。</li>
 *   <li><b>错误时先 close() 再退避重连</b>：`EventSource` 自己会在出错后
 *       自动重连，但它重连用的是**同一个 URL**（同一张已消费的 ticket），
 *       必然一直失败。所以这里主动 `close()` 掐掉浏览器的自动重连，
 *       由自己按指数退避（1s → 2s → 4s…，带抖动）重新换票建连。</li>
 *   <li><b>连续失败 3 次转轮询</b>：SSE 建不起来（代理缓冲、公司网络拦截长连接等）
 *       时不能把角标就此停更 —— 退化成每 20 秒拉一次 `/unread-count`。
 *       这是 ALIGNMENT 里写明的降级路径，也是"断网恢复后数字自动补齐"的保证。</li>
 * </ol>
 *
 * <h2>为什么依赖全部从 options 注入（不直接 import api / store）</h2>
 * 与 useDraft 同一条理由：composable 保持"纯逻辑"，验收脚本才能在 Node 里
 * 用假的 EventSource + 假时钟跑完整状态机（真实网络路径由后端脚本
 * verify-v03-sse.py 与浏览器实测覆盖）。接线在 AppLayout 一处完成。
 *
 * @author 刘
 */
import { ref } from 'vue'

export type NotificationStreamStatus = 'stopped' | 'connecting' | 'open' | 'backoff' | 'polling'

export interface UseNotificationStreamOptions {
  /** 换取一次性 ticket（需登录态） */
  fetchTicket: () => Promise<string>
  /** 收到服务端推来的未读数 */
  onUnread: (count: number) => void
  /** 轮询降级时取一次未读数（SSE 不可用期间定期调用） */
  poll: () => void | Promise<void>
  /** 连接地址构造（默认指向后端 SSE 通道；验收脚本可覆盖） */
  streamUrl?: (ticket: string) => string
  /** 退避基数（默认 1000ms，即首轮重连等 0.5~1s） */
  backoffBaseMs?: number
  /** 退避上限（默认 30000ms） */
  maxBackoffMs?: number
  /** 连续失败多少次后转轮询（默认 3） */
  failuresBeforePolling?: number
  /** 轮询间隔（默认 20000ms，只在降级模式下生效） */
  pollIntervalMs?: number
}

const DEFAULT_STREAM_URL = (ticket: string) =>
  `/api/notifications/stream?ticket=${encodeURIComponent(ticket)}`

export function useNotificationStream(options: UseNotificationStreamOptions) {
  const backoffBaseMs = options.backoffBaseMs ?? 1000
  const maxBackoffMs = options.maxBackoffMs ?? 30000
  const failuresBeforePolling = options.failuresBeforePolling ?? 3
  const pollIntervalMs = options.pollIntervalMs ?? 20000
  const buildUrl = options.streamUrl ?? DEFAULT_STREAM_URL

  const status = ref<NotificationStreamStatus>('stopped')

  let es: EventSource | null = null
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  let pollTimer: ReturnType<typeof setInterval> | null = null
  /** 连续失败次数（任何一次 open 成功都会清零） */
  let failures = 0
  let running = false
  /**
   * 代数：stop() / start() 时自增。
   *
   * 用途是丢弃"上一轮连接的迟到回调"：换票是异步的，若在换票期间用户登出
   * （stop），随后到达的 open/error 回调会把状态机又推进起来 —— 表现为
   * "登出后仍在偷偷重连"。回调里先比代数，不一致就直接忽略。
   */
  let generation = 0

  function clearRetry() {
    if (retryTimer !== null) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
  }

  function clearPoll() {
    if (pollTimer !== null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  /** 解析事件载荷；格式异常一律忽略（角标少更新一次，好过抛异常打断链路） */
  function applyUnread(raw: unknown) {
    if (typeof raw !== 'string') return
    try {
      const parsed = JSON.parse(raw) as { count?: unknown }
      if (typeof parsed.count === 'number' && Number.isFinite(parsed.count)) {
        options.onUnread(parsed.count)
      }
    } catch {
      /* 忽略 */
    }
  }

  function scheduleRetry() {
    if (!running) return
    failures += 1
    if (failures >= failuresBeforePolling) {
      enterPolling()
      return
    }
    status.value = 'backoff'
    const base = Math.min(backoffBaseMs * 2 ** (failures - 1), maxBackoffMs)
    // 半抖动：多标签同时掉线时，避免所有标签在同一毫秒一起重连（惊群）
    const delay = base / 2 + Math.random() * (base / 2)
    clearRetry()
    retryTimer = setTimeout(() => {
      retryTimer = null
      void connect()
    }, delay)
  }

  /**
   * 降级为轮询。
   *
   * 进入后<b>不再尝试 SSE</b>：连错三次说明环境留不住长连接（而不是偶发抖动），
   * 继续在后台重试只会持续制造失败请求；用户下次刷新页面时会重新尝试 SSE。
   * 先立刻 poll 一次（别让用户等一个完整间隔），再按间隔轮询。
   */
  function enterPolling() {
    if (!running || pollTimer !== null) {
      status.value = running ? 'polling' : 'stopped'
      return
    }
    clearRetry()
    status.value = 'polling'
    void options.poll()
    pollTimer = setInterval(() => {
      void options.poll()
    }, pollIntervalMs)
  }

  async function connect() {
    if (!running) return
    status.value = 'connecting'

    let ticket: string
    try {
      ticket = await options.fetchTicket()
    } catch {
      // 换票失败（后端抖动 / 会话失效 / 限流）也走退避，且计入失败次数
      scheduleRetry()
      return
    }
    if (!running) return // 换票期间被 stop() 了

    const Ctor = globalThis.EventSource
    if (!Ctor) {
      // 环境没有 EventSource（非常老的浏览器 / 非浏览器环境）：直接走轮询
      enterPolling()
      return
    }

    const current = new Ctor(buildUrl(ticket))
    es = current
    const gen = generation
    const stale = () => gen !== generation || !running

    current.addEventListener('open', () => {
      if (stale()) return
      failures = 0
      status.value = 'open'
    })

    current.addEventListener('unread', (e) => {
      if (stale()) return
      applyUnread((e as MessageEvent).data)
    })

    current.addEventListener('error', () => {
      if (stale()) return
      // 必须主动 close：否则浏览器会用同一张已消费的 ticket 无限自动重连
      current.close()
      if (es === current) es = null
      scheduleRetry()
    })
  }

  /** 降级模式下，用户切回标签页时立刻拉一次（别让回来的人看到过期角标） */
  function onVisibilityChange() {
    if (typeof document === 'undefined') return
    if (document.visibilityState === 'visible' && status.value === 'polling') {
      void options.poll()
    }
  }

  /** 开始（登录后调用）；重复调用无副作用 */
  function start() {
    if (running) return
    running = true
    generation += 1
    failures = 0
    if (typeof document !== 'undefined' && typeof document.addEventListener === 'function') {
      document.addEventListener('visibilitychange', onVisibilityChange)
    }
    void connect()
  }

  /** 停止（登出 / 组件卸载）：关连接、清定时器、摘监听 */
  function stop() {
    running = false
    generation += 1
    clearRetry()
    clearPoll()
    if (es) {
      es.close()
      es = null
    }
    if (typeof document !== 'undefined' && typeof document.removeEventListener === 'function') {
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
    status.value = 'stopped'
  }

  return { status, start, stop }
}