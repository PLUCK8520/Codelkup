/**
 * 前端验收脚本（Markdown URL 白名单）：钉死"反斜杠不能绕过协议白名单"。
 *
 * 背景（真实缺陷）：DOMPurify 之后的 `afterSanitizeAttributes` hook 用
 * SAFE_LINK_URI / SAFE_IMAGE_URI 做协议白名单，其中 `\/(?!\/)` 只拦 `//evil.com`
 * 这种协议相对 URL。但浏览器按 URL 规范把 `\` 当 `/`，于是
 * `<a href="/\evil.com">站内链接</a>` 会被解析成 `//evil.com` —— 白名单放行、点击跳外站。
 *
 * 为什么必须用真实源码 + jsdom 而不是字符串比对：这条链路是
 * marked 解析 → DOMPurify 消毒 → hook 复核，任何一环的行为（实体解码、属性白名单）
 * 都影响结论，只有跑真实模块才能验证。
 *
 * 运行：node verify-v03-markdown-uri.mjs
 * 退出码：0 = 全通过，1 = 有失败用例
 */
import { JSDOM } from 'jsdom'
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

/* ============ 浏览器环境：DOMPurify 需要一个真实 DOM ============ */
const dom = new JSDOM('<!doctype html><html><body></body></html>')
globalThis.window = dom.window
globalThis.document = dom.window.document

/* ============ 加载真实源码 ============ */
const vite = await createViteServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
})
const { renderMarkdown } = await vite.ssrLoadModule('/src/utils/markdown.ts')

const render = (md) => renderMarkdown(md)

/* 真正的安全不变量：链接**解析后**必须仍在同源。
   只看 href 字符串会误判 —— 例如 marked 会把 Markdown 语法里的 `\` 编码成 `%5C`，
   而 `%5C` 不会在 URL 解析阶段被当成斜杠，所以 `/%5Cevil.com` 仍是站内路径。 */
const ORIGIN = 'https://codelkup.test'
function hrefLeavesOrigin(html) {
  const m = html.match(/href="([^"]*)"/)
  if (!m) return false // 没有 href（已被剔除）同样安全
  try {
    return new URL(m[1], ORIGIN).origin !== ORIGIN
  } catch {
    return true // 解析不了的一律按不安全处理
  }
}

/* ============ 用例 1：本次修复的反斜杠绕过 ============ */
// 注意：JS 字符串里的 "\\" 就是单个反斜杠，即 HTML 中的 href="/\evil.com"
const bsLink = render(String.raw`<a href="/\evil.com">站内链接</a>`)
check('反斜杠链接：href 被剔除', !bsLink.includes('href='), bsLink)
check('反斜杠链接：外部域名不残留', !bsLink.includes('evil.com'), bsLink)

const bsMdLink = render(String.raw`[站内链接](/\evil.com)`)
check('反斜杠链接（Markdown 语法）：解析后不跳出本站', !hrefLeavesOrigin(bsMdLink), bsMdLink)

const bsImg = render(String.raw`<img src="/\evil.com/x.png" alt="x">`)
check('反斜杠图片：整个 img 被移除（不留裂图）', !bsImg.includes('<img'), bsImg)

/* ============ 用例 2：既有防线不回归 ============ */
const js = render('[点我](javascript:alert(1))')
check('javascript: 伪协议仍被剔除', !js.includes('href='), js)

const jsTab = render('[点我](java\tscript:alert(1))')
check('制表符混淆的 javascript: 仍被剔除', !jsTab.includes('href='), jsTab)

const dataImg = render('<img src="data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=">')
check('data: URI 图片仍被移除', !dataImg.includes('<img'), dataImg)

const prLink = render('[外站](//evil.com)')
check('协议相对 URL //evil.com 仍被剔除', !prLink.includes('href='), prLink)

/* ============ 用例 3：正常输入不能被误杀（否则"全删"也能过上面的用例） ============ */
const okHttp = render('[外链](https://example.com)')
check('https 外链保留且带 noopener', okHttp.includes('https://example.com') && okHttp.includes('noopener'), okHttp)

const okAnchor = render('[目录](#section-1)')
check('站内锚点保留', okAnchor.includes('href="#section-1"'), okAnchor)

const okRel = render('[文档](./docs/a.md)')
check('站内相对路径保留', okRel.includes('./docs/a.md'), okRel)

const okImg = render('<img src="/uploads/avatars/7/a.png" alt="头像">')
check('站内图片路径保留且补懒加载', okImg.includes('/uploads/avatars/7/a.png') && okImg.includes('loading="lazy"'), okImg)

const okXssImg = render('<img src="https://cdn.example.com/a.webp" onerror="alert(1)">')
check('外站图片保留但 onerror 被剔除', okXssImg.includes('cdn.example.com/a.webp') && !okXssImg.includes('onerror'), okXssImg)

/* ============ 收尾 ============ */
await vite.close()

console.log('\n' + '='.repeat(58))
console.log(`Markdown URL 白名单验证：${pass}/${pass + fail} 通过`)
if (failures.length) console.log('失败：' + failures.join(' | '))
console.log('='.repeat(58))
process.exit(fail ? 1 : 0)