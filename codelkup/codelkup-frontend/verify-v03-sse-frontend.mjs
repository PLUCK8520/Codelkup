/**
 * 前端验收脚本（通知实时通道 M3-2）：钉死 SSE 状态机的四条行为约定。
 *
 * 为什么要单独写这一条（而不是塞进 verify-v03-sse.py）：这是**纯前端状态机** ——
 * 建连、断线、退避、降级轮询全在浏览器侧，Python 脚本看不到。
 * 而这几条恰恰是最容易"看起来写了、实际不生效"的：
 *   1. 出错后必须主动 close() —— 否则浏览器会用同一张已消费的 ticket 无限自动重连；
 *   2. 重连要用**新 ticket**（票据一次性），不能复用旧 URL；
 *   3. 退避要真的退（不是立刻重连打满），且成功后失败计数必须清零；
 *   4. 连续失败 3 次要转轮询，且**进入时先立即拉一次**（用户不能等一个完整间隔）。
 *
 * 手法：把 globalThis.EventSource 换成假实现，直接驱动 open / error / unread，
 * 用真实定时器 + 极小的配置值（base=80ms、间隔=60ms）把上面的时序跑出来 ——
 * 与后端脚本 verify-v03-sse.py（真连接、真心跳、真 3 秒送达）互补。
 *
 * 运行：node verify-v03-sse-frontend.mjs
 * 退出码：0 = 全通过，1 = 有失败用例
 */
import { createServer as createViteServer } from './node_modules/vite/dist/node/index.js'

let pass = 0
let fail = 0
const failures = []

function check(name, ok, detail = '') {
  if (ok) {
    pass++
    console.log(`✓ PASS  ${name}`)
  } else {
    fail++
    failures.push(name)
    console.log(`✗ FAIL  ${name}${detail ? '  ->  ' + detail : ''}`)
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function waitFor(pred, timeout = 2000, step = 5) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeout) {
    if (pred()) return true
    await sleep(step)
  }
  return false
}

/* ============ 假 EventSource（记录实例、可被测试驱动） ============ */
class FakeEventSource {
  static instances = []
  constructor(url) {
    this.url = url
    this.closed = false
    this.handlers = {}
    FakeEventSource.instances.push(this)
  }
  addEventListener(type, fn) {
    this.handlers[type] = fn
  }
  close() {
    this.closed = true
  }
  // ---- 测试驱动 ----
  open() {
    this.handlers.open?.({})
  }
  fail() {
    this.handlers.error?.({})
  }
  emitUnread(data) {
    this.handlers.unread?.({ data })
  }
}
globalThis.EventSource = FakeEventSource

/* ============ 加载真实源码 ============ */
const vite = await createViteServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
})
const { useNotificationStream } = await vite.ssrLoadModule('/src/composables/useNotificationStream.ts')

/* ============ 桩：ticket / 未读数回调 ============ */
let ticketSeq = 0
let fetchCalls = 0
let fetchShouldFail = false
const unreadSeen = []
let pollCalls = 0

const options = {
  fetchTicket: async () => {
    fetchCalls++
    if (fetchShouldFail) throw new Error('后端抖动')
    return `T${++ticketSeq}`
  },
  onUnread: (n) => unreadSeen.push(n),
  poll: () => {
    pollCalls++
  },
  backoffBaseMs: 80,
  maxBackoffMs: 200,
  failuresBeforePolling: 3,
  pollIntervalMs: 60,
}

const ctl = useNotificationStream(options)
const es = () => FakeEventSource.instances
const latest = () => es().at(-1)

/* ================= A. 建连与事件解析 ================= */
console.log('\n[A] 建连与 unread 事件')

ctl.start()
check('start 后立即去换 ticket', await waitFor(() => fetchCalls === 1), `fetchCalls=${fetchCalls}`)
check('换到 ticket 后建立连接（URL 带 ticket）',
  await waitFor(() => es().length === 1) && es()[0].url.includes('ticket=T1'),
  es()[0]?.url)
check('连接中状态为 connecting', ctl.status.value === 'connecting', ctl.status.value)

latest().open()
check('open 后状态变为 open', ctl.status.value === 'open', ctl.status.value)

latest().emitUnread(JSON.stringify({ count: 5 }))
latest().emitUnread(JSON.stringify({ count: 3 }))
check('unread 事件把数字交给 onUnread（绝对值，不是增量）',
  unreadSeen.join(',') === '5,3', unreadSeen.join(','))

latest().emitUnread('{不是合法 JSON')
latest().emitUnread(JSON.stringify({ count: 'abc' }))
latest().emitUnread(JSON.stringify({ other: 1 }))
check('非法载荷被忽略且不抛（角标少更新一次好过链路被打断）',
  unreadSeen.length === 2, unreadSeen.join(','))

/* ================= B. 断线：掐掉自动重连 + 换新票 + 退避 ================= */
console.log('\n[B] 断线重连（新 ticket / 退避 / 失败计数清零）')

latest().fail()
check('出错后主动 close（否则浏览器会用已消费的 ticket 无限自动重连）',
  es()[0].closed === true, `closed=${es()[0].closed}`)
await sleep(20)
check('退避窗口内不立刻重连（不是打满请求）', es().length === 1, `实例数=${es().length}`)
check('退避到点后重连（带新的 ticket，不是旧 URL）',
  await waitFor(() => es().length === 2) && es()[1].url.includes('ticket=T2'),
  es()[1]?.url)

// 成功一次 → 失败计数清零；随后两次失败（<3）不应进入轮询
latest().open()
latest().fail()
await waitFor(() => es().length === 3, 1000)
latest().fail()
check('open 成功后失败计数清零：只数连续失败，两次失败仍不降级',
  ctl.status.value !== 'polling', ctl.status.value)

/* ================= C. 连续失败 3 次 → 转轮询 ================= */
console.log('\n[C] 降级：连续失败 3 次转轮询')

await waitFor(() => es().length === 4, 1000)
latest().fail() // 第三次连续失败
check('连续第 3 次失败后进入轮询模式', await waitFor(() => ctl.status.value === 'polling', 500),
  ctl.status.value)
check('进入轮询时先立即拉一次（不等一个完整间隔）', pollCalls >= 1, `pollCalls=${pollCalls}`)

const before = pollCalls
await waitFor(() => pollCalls >= before + 1, 500)
check('之后按间隔继续轮询', pollCalls >= before + 1, `pollCalls=${pollCalls}`)

const esCount = es().length
await sleep(200)
check('轮询期间不再尝试建立 SSE（连不上就别反复试）', es().length === esCount,
  `实例数 ${esCount} → ${es().length}`)

/* ================= D. 停止 ================= */
console.log('\n[D] stop() 与幂等')

const pollBeforeStop = pollCalls
ctl.stop()
check('stop 后状态回到 stopped', ctl.status.value === 'stopped', ctl.status.value)
await sleep(200)
check('stop 后轮询停止', pollCalls === pollBeforeStop, `${pollBeforeStop} → ${pollCalls}`)

// 幂等：重复 start 只建一条连接
FakeEventSource.instances.length = 0
ticketSeq = 0
fetchCalls = 0
ctl.start()
ctl.start()
await waitFor(() => es().length >= 1)
await sleep(50)
check('start() 幂等：重复调用不会建出第二条连接', es().length === 1, `实例数=${es().length}`)
check('重复 start 也只换一次 ticket', fetchCalls === 1, `fetchCalls=${fetchCalls}`)

ctl.stop()

// 换票失败也要走退避（后端抖动 / 会话失效的场景）
FakeEventSource.instances.length = 0
fetchShouldFail = true
fetchCalls = 0
ctl.start()
await waitFor(() => fetchCalls >= 1)
check('换票失败不会让状态机卡死（走退避、状态为 backoff 或 connecting）',
  ctl.status.value === 'backoff' || ctl.status.value === 'connecting', ctl.status.value)
ctl.stop()

/* ============ 收尾 ============ */
await vite.close()

console.log('\n' + '='.repeat(58))
console.log(`通知实时通道（前端）验证：${pass}/${pass + fail} 通过`)
if (failures.length) console.log('失败：' + failures.join(' | '))
console.log('='.repeat(58))
process.exit(fail ? 1 : 0)