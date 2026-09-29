/**
 * stats store 去重与降级验证（2026-09-29）
 *
 * 背景：`GET /api/stats` 是"全站汇总"，与页面 / 分页 / tab 都无关，却出现在四处 ——
 * 页脚（每个页面）、首页 Hero、问答页、标签页。修复前的实际行为：
 *   · 首页 / 标签页：页脚 + 页面各请求一次 → 同一个 URL 两遍；
 *   · 问答页：stats 被塞进 `load()` 的 `Promise.all` → **每翻一页、每切一次 tab 都重拉**，
 *     而服务端这个接口是 3 条 COUNT 且刻意不缓存。
 * 修复后收敛到 `stores/stats.ts`：在途去重 + 30s TTL + 失败静默。
 *
 * 为什么必须跑真实模块而不是读源码字符串：
 * 正确性全在**时序**上 —— 并发调用是否共用同一个 Promise、缓存窗口是否按时间算、
 * 失败时是否退回旧值而不抛错。这三点读代码看不出来，必须把真源码跑起来。
 *
 * 计数方式：替换 axios 的 adapter，把"真正出网的调用"数下来（不去 mock store 内部的
 * statsApi —— 那样测的是"我以为的实现"）。
 * ⚠️ adapter 的安装必须走 verify-stats-dedup.entry.ts：脚本自己 import axios 拿到的是
 * ESM 构建，与 Vite SSR 外部化后 request.ts 用的不是同一实例，直接改 defaults 会静默失效。
 *
 * 运行：node verify-stats-dedup.mjs
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

/* ============ 环境桩 ============ */
// request.ts 的 tokenStorage 用的是 **sessionStorage**（access token 不进 localStorage），
// 请求拦截器第一件事就是读它 —— 桩漏了这个，请求会在拦截器里就抛
// "sessionStorage is not defined"，根本到不了 adapter（表现为"0 次出网"）
const makeStorage = () => {
  const kv = new Map()
  return {
    getItem: (k) => (kv.has(k) ? kv.get(k) : null),
    setItem: (k, v) => kv.set(k, String(v)),
    removeItem: (k) => kv.delete(k),
    clear: () => kv.clear(),
    key: (i) => [...kv.keys()][i] ?? null,
    get length() {
      return kv.size
    },
  }
}
globalThis.sessionStorage = makeStorage()
globalThis.localStorage = makeStorage()

/* ============ 出网计数用的 adapter ============ */
const STATS_BODY = {
  code: 0,
  message: 'success',
  data: {
    boardCount: 10,
    boardPostCount: 10545,
    tagCount: 12,
    questionCount: 2093,
    questionSolvedCount: 73,
    questionUnsolvedCount: 2020,
  },
}

let calls = 0
let failNext = false
let delayMs = 0
/** 由夹具在 prepare 后注入（脚本自己不 import axios，避免踩"两份实例"的坑） */
let AxiosErrorCtor = null

const countingAdapter = async (config) => {
  calls++
  if (delayMs) await sleep(delayMs)
  if (failNext) {
    // ⚠️ 必须按 axios 的 adapter 契约抛 **带 config 的 AxiosError**。
    // 真实 adapter（浏览器 xhr / Node http）失败时都是这么抛的；
    // 若这里抛裸 Error，`error.config` 会整个丢失，请求拦截链就无从判断
    // 这是不是一个 silent 请求 —— 那属于"测试造出来的失败"，不是真实缺陷。
    throw new AxiosErrorCtor('Network Error', AxiosErrorCtor.ERR_NETWORK, config, {})
  }
  return { data: STATS_BODY, status: 200, statusText: 'OK', headers: {}, config }
}

/* ============ 加载真实源码 ============ */
const vite = await createViteServer({
  root: process.cwd(),
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error',
  // ⚠️ 必须让 Vite 自己处理 axios：默认 SSR 会把 node_modules 外部化，
  //    于是夹具里的 axios 与 request.ts 里的 axios 可能是两份不同的模块实例，
  //    `axios.defaults.adapter` 改了也不会被对方看到（表现为"0 次出网"）
  ssr: { noExternal: ['axios'] },
})

const fixture = await vite.ssrLoadModule('/verify-stats-dedup.entry.ts')
AxiosErrorCtor = fixture.axios.AxiosError
await fixture.prepare(countingAdapter)
const freshStore = fixture.freshStore

// 把 ElMessage.error 换成记录器：既能断言 silent 契约，也避免 Node 里真弹提示而抛 document 未定义
const message = fixture.spyMessage()

console.log('=== stats store 去重与降级 ===')

// 冒烟：先确认 adapter 真的被用上了（否则后面所有计数都会是 0，测试自己先失败一次再说）
{
  calls = 0
  const smoke = await fixture.probe()
  check('T0 请求链路已接通（adapter 生效）', smoke.ok === true, JSON.stringify(smoke).slice(0, 200))
  check('T0b 计数生效', calls === 1, `实际 ${calls} 次`)
}

/* ---------- T1：并发调用只发一次请求（在途去重） ---------- */
calls = 0
delayMs = 30 // 拉长在途窗口，确保并发确实重叠
const store = freshStore()
const concurrent = await Promise.all([
  store.ensure(),
  store.ensure(),
  store.ensure(),
  store.ensure(),
  store.ensure(),
])
check('T1 并发 5 次 ensure 只出网 1 次', calls === 1, `实际 ${calls} 次`)
check('T1b 并发调用拿到同一份数据', concurrent.every((s) => s && s.boardPostCount === 10545))
check('T1c store.data 已写入且字段正确', store.data?.tagCount === 12 && store.data?.questionSolvedCount === 73)

/* ---------- T2：TTL 内复用缓存（不再出网） ---------- */
delayMs = 0
calls = 0
// 前置断言：没有这一条，上面 T1 失败时 calls 本来就是 0，"不出网"会假通过
check('T2a 前置：数据已成功加载', store.data !== null, `data=${JSON.stringify(store.data)}`)
await store.ensure()
await store.ensure()
check('T2 TTL 内重复调用不出网', calls === 0, `实际 ${calls} 次`)

/* ---------- T3：冷启动并发 + 随后复用，总计只出网 1 次 ---------- */
{
  const fresh = freshStore()
  calls = 0
  delayMs = 30
  await Promise.all([fresh.ensure(), fresh.ensure(), fresh.ensure()])
  delayMs = 0
  await Promise.all([fresh.ensure(), fresh.ensure()])
  check('T3 冷启动并发 + 随后复用，总计只出网 1 次', calls === 1, `实际 ${calls} 次`)
}

/* ---------- T4：force 可以穿透缓存 ---------- */
{
  const s = freshStore()
  calls = 0
  await s.ensure()
  await s.ensure(true)
  check('T4 force=true 会再出网一次（受控穿透）', calls === 2, `实际 ${calls} 次`)
}

/* ---------- T5：失败静默 —— 不抛异常、退回已有值 ---------- */
{
  const s = freshStore()
  calls = 0
  const ok = await s.ensure()
  check('T5a 首次成功拿到数据', ok?.boardCount === 10, `拿到 ${JSON.stringify(ok)}`)

  failNext = true
  let threw = null
  let afterFail = null
  try {
    afterFail = await s.ensure(true) // 强制回源 → 撞上失败
  } catch (e) {
    threw = e
  }
  failNext = false
  check('T5b 失败不抛异常（页面不会被统计接口拖崩）', threw === null, String(threw))
  check('T5c 失败时退回上一次的成功值', afterFail?.boardCount === 10, `拿到 ${JSON.stringify(afterFail)}`)
  check('T5d 失败后 store.data 仍是旧值（不置空）', s.data?.boardCount === 10)
}

/* ---------- T6：首次就失败 → 返回 null（调用方据此降级，不显示 0） ---------- */
{
  const s = freshStore()
  failNext = true
  const v = await s.ensure()
  failNext = false
  check('T6 首次失败返回 null（不是 0，也不是抛错）', v === null)
  check('T6b store.data 保持 null', s.data === null)
}

/* ---------- T7：silent 契约 —— 失败时不能弹全局提示 ---------- */
{
  const s = freshStore()
  message.log.length = 0
  failNext = true
  await s.ensure()
  failNext = false
  check('T7 silent 请求失败时不弹全局提示', message.log.length === 0, message.log.join(' | '))
}

/* ---------- 结构断言：调用点都已收敛到 store ---------- */
{
  const { readFileSync } = await import('node:fs')
  const files = [
    'src/layouts/AppLayout.vue',
    'src/views/home/HomeView.vue',
    'src/views/question/QuestionListView.vue',
    'src/views/tag/TagListView.vue',
  ]
  const offenders = []
  for (const f of files) {
    // ⚠️ 先剥离注释再断言：本项目踩过"注释里写着 v-html 说明文字被误判"的坑
    const src = readFileSync(f, 'utf8')
      .replace(/<!--[\s\S]*?-->/g, '')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/^\s*\/\/.*$/gm, '')
    if (/statsApi\s*\.\s*overview/.test(src)) offenders.push(f)
  }
  check('S1 四个调用点都已改走 store，无遗留的直接调用', offenders.length === 0, offenders.join(', '))
}

message.restore()
await vite.close()

console.log('='.repeat(58))
console.log(`stats 去重与降级 | 用例总数 ${pass + fail}  |  PASS=${pass}  FAIL=${fail}`)
console.log('='.repeat(58))
if (fail > 0) {
  console.log('失败用例：\n  - ' + failures.join('\n  - '))
  process.exit(1)
}
