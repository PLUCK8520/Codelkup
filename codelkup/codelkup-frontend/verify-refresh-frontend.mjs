/**
 * 前端验收脚本（凭证刷新链路）：accessToken 失效 → 自动刷新 → 重放原请求。
 *
 * 为什么必须单独测这条链路：后端把业务失败也返回 HTTP 200 + code，
 * 所以"凭证失效"是 `200 + 40101` 而不是 `401`。修复前 request.ts 只判
 * `status === 401`，链路**从不触发** —— 编译通过、页面能跑，都证明不了它有效；
 * 一旦失效，表现为"token 一过期就被当成未登录"，而刷新串行化的设计等于没生效。
 *
 * M4-15 追加用例 5：`silent` 请求（草稿自动保存等后台行为）失败时**不弹全局提示**。
 * 这条同样是"看代码能懂、但必须实测"的行为 —— 提示挂在两个分支里
 * （业务码分支在 request()、HTTP 错误分支在响应拦截器），漏掉任何一处都会
 * 让自动保存每 15 秒弹一次"保存失败"，而单测/构建都无法发现。
 * 对照手法：同一个失败接口，非 silent 请求必须弹（证明探针有效）、silent 请求必须不弹。
 *
 * 契约来源：mock 后端复刻的是真后端行为（该契约已由后端 verify-sprint1.py 对真服务验证）：
 *   - GET  /api/users/me      ：token 不是最新的那个 → 200 + code 40101
 *   - POST /api/auth/refresh  ：refreshToken 有效 → 200 + 新 token 对；无效 → 200 + code 40102
 *
 * 用 mock 而非真后端的理由：本脚本属于前端纯逻辑套件（不依赖 Java 服务与数据库），
 * 用 alias 把 axios 换成打了 baseURL 的实例即可完整走通拦截器链路。
 *
 * 运行：node verify-refresh-frontend.mjs
 * 退出码：0 = 全通过，1 = 有失败用例
 */
import { createServer as createHttpServer } from 'node:http'
import { writeFileSync, unlinkSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { tmpdir } from 'node:os'
import { createServer as createViteServer } from './node_modules/vite/dist/node/index.js'

const PORT = 8099
const NEW_ACCESS = 'NEW_ACCESS_TOKEN'
const NEW_REFRESH = 'NEW_REFRESH_TOKEN'

let refreshCalls = 0
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

/** refresh 请求实际收到的 body：用例 1 断言它不含 refreshToken（第五轮审计 #3 后走 Cookie） */
let lastRefreshBody = ''
/** 模拟"Cookie 里的 refresh 也失效"（用例 4）—— Node 沙箱没有真实 Cookie 罐 */
globalThis.__REFRESH_FAIL__ = false

/* ============ mock 后端 ============ */
const backend = createHttpServer((req, res) => {
  const send = (body) => {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(body))
  }
  const auth = req.headers.authorization || ''

  if (req.url.startsWith('/api/auth/refresh')) {
    refreshCalls++
    let raw = ''
    req.on('data', (c) => (raw += c))
    req.on('end', () => {
      // 第五轮审计 #3：refresh 走 HttpOnly Cookie，前端不再带 body ——
      // 这里记录实际收到的 body 供用例 1 断言；"Cookie 是否有效"由开关模拟。
      lastRefreshBody = raw
      if (globalThis.__REFRESH_FAIL__) {
        send({ code: 40102, message: '登录凭证无效', data: null })
      } else {
        send({ code: 0, message: 'success', data: { accessToken: NEW_ACCESS, refreshToken: NEW_REFRESH } })
      }
    })
    return
  }

  if (req.url.startsWith('/api/boom')) {
    // HTTP 层错误（走 axios 失败分支）：用来验证 silent 标志在这一支也生效
    res.writeHead(500, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ code: 50001, message: '服务器开小差了，请稍后再试', data: null }))
    return
  }

  if (req.url.startsWith('/api/users/me')) {
    // 只有拿到刷新后的新 token 才算通过
    if (auth === 'Bearer ' + NEW_ACCESS) {
      send({ code: 0, message: 'success', data: { id: 1, nickname: '阿布' } })
    } else {
      send({ code: 40101, message: '未登录或登录已过期', data: null })
    }
    return
  }
  send({ code: 40001, message: '接口不存在', data: null })
})
await new Promise((r) => backend.listen(PORT, '127.0.0.1', r))

/* ============ 桩：element-plus / axios（打 baseURL） ============ */
// ⚠️ 桩文件写系统临时目录，不落到项目里 —— 否则中途异常退出会留下 .tmp-* 残骸
// 提示桩把调用记到 globalThis（与脚本同一个 JS realm），用例 5 据此断言"弹了 / 没弹"
const STUB_EP = join(tmpdir(), 'codelkup-stub-element-plus.mjs')
const STUB_AX = join(tmpdir(), 'codelkup-stub-axios.mjs')
// 桩在临时目录里，引用项目的 axios 必须用绝对 file:// URL（相对路径会解析到临时目录）
const AXIOS_ENTRY = pathToFileURL(resolve('node_modules/axios/index.js')).href
writeFileSync(
  STUB_EP,
  `export const ElMessage = {\n` +
    `  error(m) { (globalThis.__elMsgs ||= []).push(m) },\n` +
    `  success() {},\n` +
    `  warning() {},\n` +
    `}\n` +
    `export default { ElMessage }\n`,
)
writeFileSync(
  STUB_AX,
  `import axios from ${JSON.stringify(AXIOS_ENTRY)}\n` +
    `// doRefresh 用的是"裸 axios + 相对路径"，在 Node 里必须给全局实例一个 baseURL\n` +
    `axios.defaults.baseURL = 'http://127.0.0.1:${PORT}'\n` +
    `export * from ${JSON.stringify(AXIOS_ENTRY)}\n` +
    `export default axios\n`,
)

/* ============ 浏览器全局的最小桩（request.ts 用到 localStorage（清旧键）/ sessionStorage / location） ============ */
const kv = new Map()
globalThis.localStorage = {
  getItem: (k) => (kv.has(k) ? kv.get(k) : null),
  setItem: (k, v) => kv.set(k, String(v)),
  removeItem: (k) => kv.delete(k),
  clear: () => kv.clear(),
}
// 第五轮审计 #3：accessToken 改存 sessionStorage（refresh 不落本地，在 HttpOnly Cookie 里）
const skv = new Map()
globalThis.sessionStorage = {
  getItem: (k) => (skv.has(k) ? skv.get(k) : null),
  setItem: (k, v) => skv.set(k, String(v)),
  removeItem: (k) => skv.delete(k),
  clear: () => skv.clear(),
}
const redirects = []
globalThis.location = { pathname: '/posts/1', replace: (u) => redirects.push(u) }

/* ============ 加载真实源码 ============ */
const vite = await createViteServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
  resolve: { alias: { axios: STUB_AX, 'element-plus': STUB_EP } },
})
const mod = await vite.ssrLoadModule('/src/utils/request.ts')
mod.default.defaults.baseURL = `http://127.0.0.1:${PORT}/api`

/* ============ 用例 1：accessToken 过期 + HttpOnly Cookie 有效 → 自动刷新并重放 ============ */
skv.set('codelkup_access_token', 'EXPIRED_ACCESS')
const me = await mod.get('/users/me')
check('刷新后重放原请求成功（拿到本人资料）', me && me.id === 1, JSON.stringify(me))
check('sessionStorage 的 accessToken 已被替换为新 token', skv.get('codelkup_access_token') === NEW_ACCESS)
check('刷新请求不带 refreshToken（走 HttpOnly Cookie）',
  !('refreshToken' in JSON.parse(lastRefreshBody || '{}')), 'body=' + lastRefreshBody)
check('refresh 恰好被调用 1 次', refreshCalls === 1, '实际 ' + refreshCalls)
check('已登录场景未发生重定向', redirects.length === 0, redirects.join(','))

/* ============ 用例 2：并发两个请求同时失效 → 只刷新一次（单槽位不被互相顶掉）============ */
skv.set('codelkup_access_token', 'EXPIRED_ACCESS')
const before = refreshCalls
const [r1, r2] = await Promise.all([mod.get('/users/me'), mod.get('/users/me')])
check('并发失效：两个请求都成功', r1?.id === 1 && r2?.id === 1)
check('并发失效：refresh 只调用 1 次（串行化生效）', refreshCalls - before === 1, '实际 ' + (refreshCalls - before))

/* ============ 用例 3：游客（本地无 access = 从未登录）→ 抛 40101、不刷新、不跳登录页 ============ */
kv.clear()
skv.clear()
const beforeRedirects = redirects.length
const beforeRefresh = refreshCalls
let err3 = null
try {
  await mod.get('/users/me')
} catch (e) {
  err3 = e
}
check('游客（无 access）→ 抛出业务码 40101', err3 && err3.code === 40101, err3 && err3.code)
check('游客不被重定向到登录页（避免首页被弹走）', redirects.length === beforeRedirects)
check('游客路径不触发 refresh（本地无 access 即不尝试刷 Cookie）', refreshCalls === beforeRefresh, '实际 ' + refreshCalls)

/* ============ 用例 4：Cookie 里的 refresh 也失效 → 抛原请求 40101 + 清凭证 + 跳登录页 ============ */
skv.set('codelkup_access_token', 'EXPIRED_ACCESS')
globalThis.__REFRESH_FAIL__ = true
let err4 = null
try {
  await mod.get('/users/me')
} catch (e) {
  err4 = e
}
// 刷新失败时向上抛的是**原请求**的业务码（40101），而不是 refresh 接口的 40102 ——
// 这是刻意的：调用方只需要知道"这次请求没通过、会话已失效"，
// 不该拿到一个它从没调用过的接口的错误码。
check('refresh 失效 → 抛原请求的 40101（刷新失败已转为重新登录）', err4 && err4.code === 40101, err4 && err4.code)
check('refresh 失效 → 本地凭证被清空', !skv.get('codelkup_access_token'))
check('登录态失效 → 跳转登录页并带回跳地址',
  redirects.length > beforeRedirects && redirects[redirects.length - 1].includes('/login?redirect='),
  redirects[redirects.length - 1])

/* ============ 用例 5：silent 请求失败不弹全局提示（草稿自动保存的失败降级）============ */
globalThis.__REFRESH_FAIL__ = false // 复位，不影响后续用例
globalThis.__elMsgs = []
kv.clear() // 游客态即可：本组用例只看提示弹没弹
skv.clear()

// 对照组：同一个"业务码失败"的接口，非 silent 请求**必须**弹 —— 证明探针真的有效
let err5 = null
try {
  await mod.get('/not-exist')
} catch (e) {
  err5 = e
}
check('对照：非 silent 业务失败会弹全局提示', globalThis.__elMsgs.length === 1, JSON.stringify(globalThis.__elMsgs))
check('对照：业务失败仍按业务码抛出（提示不影响错误传播）', err5 && err5.code === 40001, err5 && err5.code)

const before5 = globalThis.__elMsgs.length
try {
  await mod.request({ method: 'GET', url: '/not-exist', silent: true })
} catch {
  /* 预期抛错 */
}
check('silent 业务失败（HTTP 200 + 40001）不弹提示', globalThis.__elMsgs.length === before5,
  JSON.stringify(globalThis.__elMsgs.slice(before5)))

try {
  await mod.request({ method: 'GET', url: '/boom', silent: true })
} catch {
  /* 预期抛错 */
}
check('silent HTTP 500 失败同样不弹提示（拦截器分支也认这个标志）',
  globalThis.__elMsgs.length === before5, JSON.stringify(globalThis.__elMsgs.slice(before5)))

// 对照组：同一条 /boom 换成非 silent，提示要出现 —— 否则上一条可能是"接口根本没报错"
try {
  await mod.get('/boom')
} catch {
  /* 预期抛错 */
}
check('对照：非 silent 的 HTTP 500 会弹提示', globalThis.__elMsgs.length === before5 + 1,
  JSON.stringify(globalThis.__elMsgs.slice(before5)))

/* ============ 收尾 ============ */
await vite.close()
backend.close()
unlinkSync(STUB_EP)
unlinkSync(STUB_AX)

console.log('\n' + '='.repeat(58))
console.log(`凭证刷新链路验证：${pass}/${pass + fail} 通过`)
if (failures.length) console.log('失败：' + failures.join(' | '))
console.log('='.repeat(58))
process.exit(fail ? 1 : 0)