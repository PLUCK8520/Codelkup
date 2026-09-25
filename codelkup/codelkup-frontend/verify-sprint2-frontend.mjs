/**
 * Sprint 2 · 前端验收脚本
 *
 * 覆盖 DEV-DOC §13.4 中「2.5 Markdown 渲染前端接入（含 XSS 消毒）」的验收标准：
 * 用 vite 的 ssrLoadModule 加载**真实源码** `src/utils/markdown.ts`，
 * 在 jsdom 环境里跑全套用例 —— 不重写消毒逻辑，避免「测的是我以为的实现」。
 *
 * 运行（需要出口代理，见 README）：
 *   HTTPS_PROXY=http://127.0.0.1:61888 HTTP_PROXY=http://127.0.0.1:61888 \
 *     node verify-sprint2-frontend.mjs
 *
 * 退出码：0 = 全通过，1 = 有失败用例（可直接接进 CI）。
 */
import { createServer } from './node_modules/vite/dist/node/index.js'
import { JSDOM } from './node_modules/jsdom/lib/api.js'

/* ============================ 1. 最小浏览器环境 ============================ */
const dom = new JSDOM('<!DOCTYPE html><body></body>', { url: 'http://localhost/' })

// Node 22 里 navigator 是只读全局访问器，直接赋值抛 TypeError，
// 因此统一走 defineProperty 覆盖。
function expose(name, value) {
  try {
    Object.defineProperty(globalThis, name, { value, writable: true, configurable: true })
  } catch {
    /* 覆盖不了就跳过，不影响用例 */
  }
}
for (const key of [
  'window', 'document', 'navigator', 'DOMParser', 'Node', 'Element',
  'HTMLElement', 'NodeFilter', 'HTMLBodyElement', 'HTMLDocument',
  'DocumentFragment', 'Text', 'Comment', 'XMLSerializer',
]) {
  expose(key, dom.window[key])
}
expose('window', dom.window)

/* ============================ 2. 加载真实模块 ============================ */
const server = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
})
const { renderMarkdown } = await server.ssrLoadModule('/src/utils/markdown.ts')

/* ============================ 3. 用例表 ============================ */
/**
 * [名称, 输入, 是否应产出可见内容]
 *   - 恶意载荷：shouldRender=false，只要求「不残留危险内容」
 *   - 正常语法：shouldRender=true，还要真的渲染出东西
 *     （防止"消毒把好内容也删光"这类反向 bug）
 */
const cases = [
  /* --- 标签 / 属性层注入 --- */
  ['img onerror 注入', '<img src=x onerror=alert(1)>', false],
  ['script 标签', '<script>alert(1)</script>hello', false],
  ['svg onload 注入', '<svg onload=alert(1)></svg>', false],
  ['iframe 嵌入', '<iframe src="https://evil.com"></iframe>', false],
  ['onclick 行内事件', '<a href="https://ok.com" onclick="alert(1)">link</a>', false],
  ['style 属性利用', '<p style="background:url(javascript:alert(1))">x</p>', false],
  ['form 钓鱼表单', '<form action="https://evil.com"><input name="pw"></form>', false],

  /* --- URI 协议：可执行伪协议 --- */
  ['javascript: 链接', '[click](javascript:alert(1))', false],
  ['javascript: 图片', '![x](javascript:alert(1))', false],
  ['vbscript: 链接', '[click](vbscript:msgbox(1))', false],
  ['file: 链接', '[click](file:///C:/Windows/System32/drivers/etc/hosts)', false],
  ['blob: 图片', '![x](blob:https://evil.com/abc-123)', false],

  /* --- URI 协议：data: 系列（DOMPurify 对 img 的 data: 有特例放行，必须自己拦） --- */
  ['data: text/html 图片', '![x](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)', false],
  ['data: svg+xml 图片', '![x](data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9YWxlcnQoMSk+PC9zdmc+)', false],
  ['data: png 图片', '![x](data:image/png;base64,iVBORw0KGgo=)', false],
  ['data: 文本链接', '[click](data:text/html,<script>alert(1)</script>)', false],

  /* --- URI 协议：协议相对 URL（`//host` 会继承当前页协议） --- */
  ['协议相对图片 //evil.com', '![x](//evil.com/track.png)', false],
  ['协议相对链接 //evil.com', '[click](//evil.com/phish)', false],

  /* --- URI 协议：混淆变体 --- */
  ['大小写混淆 JaVaScRiPt:', '[click](JaVaScRiPt:alert(1))', false],
  ['内嵌换行 java\\nscript:', '<a href="java\nscript:alert(1)">click</a>', false],
  ['内嵌制表符 java\\tscript:', '<a href="java\tscript:alert(1)">click</a>', false],
  ['HTML 实体 &#106;avascript:', '<a href="&#106;avascript:alert(1)">click</a>', false],
  ['HTML 实体 &colon;', '<a href="javascript&colon;alert(1)">click</a>', false],
  ['十六进制实体 &#x6A;avascript:', '<a href="&#x6A;avascript:alert(1)">click</a>', false],
  ['协议前导空格 "  javascript:"', '[click](  javascript:alert(1))', false],

  /* --- 正常语法：必须渲染出内容 --- */
  ['正常 Markdown', '# 标题\n\n**粗体** 和 `code`\n\n```java\nint a=1;\n```\n\n- 列表项', true],
  ['正常外链', '[官网](https://example.com)', true],
  ['正常 http 外链', '[官网](http://example.com)', true],
  ['协议大写字形 HTTPS', '[官网](HTTPS://example.com)', true],
  ['正常图片', '![配图](https://example.com/a.png)', true],
  ['站内绝对路径图片', '![配图](/uploads/avatar/abc.png)', true],
  ['相对路径图片 ./', '![配图](./assets/a.png)', true],
  ['相对路径图片 ../', '![配图](../assets/a.png)', true],
  ['相对路径链接 ./', '[文档](./docs/a.md)', true],
  ['锚点链接', '[回到顶部](#top)', true],
  ['mailto 链接', '[联系我](mailto:a@b.com)', true],
  ['tel 链接', '[打电话](tel:+8613800138000)', true],
  ['正常表格', '| a | b |\n|---|---|\n| 1 | 2 |', true],
]

/** 危险特征：出现任意一项即判定该用例失败 */
const DANGEROUS =
  /<script|<iframe|onerror\s*=|onload\s*=|onclick\s*=|javascript:|vbscript:|data:text\/html|data:image\/svg/i

/** src/href 里不允许出现 data: 或协议相对 URL（`//host`） */
const FORBIDDEN_URI_ATTR = /(?:src|href)\s*=\s*"(?:data:|\/\/)/i

/* ============================ 4. 执行 ============================ */
let pass = 0
let fail = 0
const failures = []

for (const [name, input, shouldRender] of cases) {
  let out = ''
  let err = null
  try {
    out = renderMarkdown(input)
  } catch (e) {
    err = e
  }

  const hasDanger = DANGEROUS.test(out) || FORBIDDEN_URI_ATTR.test(out)
  const produced = out.trim().length > 0
  const ok = !err && (shouldRender ? !hasDanger && produced : !hasDanger)

  const icon = ok ? '✓' : '✗'
  console.log(`${icon} ${ok ? 'PASS' : 'FAIL'}  ${name}`)
  if (err) console.log(`        异常: ${err.message}`)
  console.log(`        输入: ${JSON.stringify(input.slice(0, 70))}`)
  console.log(`        输出: ${JSON.stringify(out.slice(0, 130))}`)

  if (ok) pass++
  else {
    fail++
    failures.push(name)
  }
}

await server.close()

/* ============================ 5. 汇总 ============================ */
console.log('\n' + '='.repeat(58))
console.log(`Sprint2 前端验收 · Markdown + XSS 消毒`)
console.log(`用例总数 ${cases.length}  |  PASS=${pass}  FAIL=${fail}`)
if (fail > 0) console.log(`失败用例: ${failures.join(' / ')}`)
console.log('='.repeat(58))

process.exit(fail > 0 ? 1 : 0)
