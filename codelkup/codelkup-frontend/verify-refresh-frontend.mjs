/**
 * 前端验收脚本（凭证刷新链路）：accessToken 失效 → 自动刷新 → 重放原请求。
 *
 * 为什么必须单独测这条链路：后端把业务失败也返回 HTTP 200 + code，
 * 所以"凭证失效"是 `200 + 40101` 而不是 `401`。修复前 request.ts 只判
 * `status === 401`，链路**从不触发** —— 编译通过、页面能跑，都证明不了它有效；
 * 一旦失效，表现为"token 一过期就被当成未登录"，而刷新串行化的设计等于没生效。
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
      const rt = raw ? JSON.parse(raw).refreshToken : null
      if (rt === 'BAD_REFRESH') {
        send({ code: 40102, message: '登录凭证无效', data: null })
      } else {
        send({ code: 0, message: 'success', data: { accessToken: NEW_ACCESS, refreshToken: NEW_REFRESH } })
      }
    })
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
const STUB_EP = join(tmpdir(), 'codelkup-stub-element-plus.mjs')
const STUB_AX = join(tmpdir(), 'codelkup-stub-axios.mjs')
// 桩在临时目录里，引用项目的 axios 必须用绝对 file:// URL（相对路径会解析到临时目录）
const AXIOS_ENTRY = pathToFileURL(resolve('node_modules/axios/index.js')).href
writeFileSync(STUB_EP, `export const ElMessage = { error() {}, success() {}, warning() {} }\nexport default { ElMessage }\n`)
writeFileSync(
  STUB_AX,
  `import axios from ${JSON.stringify(AXIOS_ENTRY)}\n` +
    `// doRefresh 用的是"裸 axios + 相对路径"，在 Node 里必须给全局实例一个 baseURL\n` +
    `axios.defaults.baseURL = 'http://127.0.0.1:${PORT}'\n` +
    `export * from ${JSON.stringify(AXIOS_ENTRY)}\n` +
    `export default axios\n`,
)

/* ============ 浏览器全局的最小桩（request.ts 只用到 localStorage / location） ============ */
const kv = new Map()
globalThis.localStorage = {
  getItem: (k) => (kv.has(k) ? kv.get(k) : null),
  setItem: (k, v) => kv.set(k, String(v)),
  removeItem: (k) => kv.delete(k),
  clear: () => kv.clear(),
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

/* ============ 用例 1：accessToken 过期 + refreshToken 有效 → 自动刷新并重放 ============ */
kv.set('codelkup_access_token', 'EXPIRED_ACCESS')
kv.set('codelkup_refresh_token', 'VALID_REFRESH')
const me = await mod.get('/users/me')
check('刷新后重放原请求成功（拿到本人资料）', me && me.id === 1, JSON.stringify(me))
check('本地 accessToken 已被替换为新 token', kv.get('codelkup_access_token') === NEW_ACCESS)
check('本地 refreshToken 已轮换', kv.get('codelkup_refresh_token') === NEW_REFRESH)
check('refresh 恰好被调用 1 次', refreshCalls === 1, '实际 ' + refreshCalls)
check('已登录场景未发生重定向', redirects.length === 0, redirects.join(','))

/* ============ 用例 2：并发两个请求同时失效 → 只刷新一次（单槽位不被互相顶掉）============ */
kv.set('codelkup_access_token', 'EXPIRED_ACCESS')
kv.set('codelkup_refresh_token', 'VALID_REFRESH')
const before = refreshCalls
const [r1, r2] = await Promise.all([mod.get('/users/me'), mod.get('/users/me')])
check('并发失效：两个请求都成功', r1?.id === 1 && r2?.id === 1)
check('并发失效：refresh 只调用 1 次（串行化生效）', refreshCalls - before === 1, '实际 ' + (refreshCalls - before))

/* ============ 用例 3：游客（无 refreshToken）→ 抛 40101 且**不**跳登录页 ============ */
kv.clear()
kv.set('codelkup_access_token', 'EXPIRED_ACCESS')
const beforeRedirects = redirects.length
let err3 = null
try {
  await mod.get('/users/me')
} catch (e) {
  err3 = e
}
check('游客无 refreshToken → 抛出业务码 40101', err3 && err3.code === 40101, err3 && err3.code)
check('游客不被重定向到登录页（避免首页被弹走）', redirects.length === beforeRedirects)
check('游客路径未打 refresh 接口', refreshCalls === before + 1, '实际 ' + refreshCalls)

/* ============ 用例 4：refreshToken 也失效 → 抛 40102 + 清空凭证 + 跳登录页 ============ */
kv.set('codelkup_access_token', 'EXPIRED_ACCESS')
kv.set('codelkup_refresh_token', 'BAD_REFRESH')
let err4 = null
try {
  await mod.get('/users/me')
} catch (e) {
  err4 = e
}
// 刷新失败时向上抛的是**原请求**的业务码（40101），而不是 refresh 接口的 40102 ——
// 这是刻意的：调用方只需要知道"这次请求没通过、会话已失效"，
// 不该拿到一个它从没调用过的接口的错误码。
check('refreshToken 失效 → 抛原请求的 40101（刷新失败已转为重新登录）', err4 && err4.code === 40101, err4 && err4.code)
check('refreshToken 失效 → 本地凭证被清空', !kv.get('codelkup_access_token') && !kv.get('codelkup_refresh_token'))
check('登录态失效 → 跳转登录页并带回跳地址',
  redirects.length > beforeRedirects && redirects[redirects.length - 1].includes('/login?redirect='),
  redirects[redirects.length - 1])

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