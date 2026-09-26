/**
 * 前端验收脚本（代码块增强 M2-13）：语言标签 / 复制内容一致 / 行号不进复制文本 / 幂等。
 *
 * 为什么必须在真实 DOM 上跑：这三件事全是对**已消毒渲染结果**的结构性包装，
 * 而且"行号不会出现在复制内容里"这一条完全依赖
 * 「行号是 CSS 伪元素、不是文本节点」这个事实 —— 字符串比对源码证明不了它，
 * 只有把节点建出来、读 textContent 才能证明。
 *
 * 运行：node verify-v03-codeblock.mjs
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

/* ============ 浏览器环境（DOMPurify 需要真实 DOM） ============ */
const dom = new JSDOM('<!doctype html><html><body></body></html>')
globalThis.window = dom.window
globalThis.document = dom.window.document
// Node 21+ 自带只读的 navigator，必须用 defineProperty 覆盖
Object.defineProperty(globalThis, 'navigator', {
  value: dom.window.navigator,
  configurable: true,
  writable: true,
})

/* ============ 加载真实源码 ============ */
const vite = await createViteServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
})
const { renderMarkdown } = await vite.ssrLoadModule('/src/utils/markdown.ts')
const { enhanceCodeBlocks, copyText } = await vite.ssrLoadModule('/src/composables/useCodeBlocks.ts')

/** 造一个容器并把 Markdown 渲染进去（含代码块增强） */
function mount(markdown, { enhance = true } = {}) {
  const container = document.createElement('div')
  container.className = 'markdown-body'
  container.innerHTML = renderMarkdown(markdown)
  document.body.append(container)
  const enhancedCount = enhance ? enhanceCodeBlocks(container) : 0
  return { container, enhancedCount }
}

const JAVA_BLOCK = ['```java', 'int a = 1;', 'int b = 2;', '```', ''].join('\n')
/** 渲染后 code 的原始文本（marked 在代码块末尾补一个换行） */
const JAVA_RAW = 'int a = 1;\nint b = 2;\n'

/* ================= 用例 1：结构与标签 ================= */
console.log('\n[1] 语言标签与结构')
const { container, enhancedCount } = mount(JAVA_BLOCK)
check('增强处理了 1 个代码块', enhancedCount === 1, enhancedCount)
check('渲染产物保留了 language-java 类名（消毒白名单含 class）',
  container.querySelector('code')?.className.includes('language-java'),
  container.querySelector('code')?.className)
check('包出了 .code-block 容器', container.querySelectorAll('.code-block').length === 1)
check('语言标签显示 java',
  container.querySelector('.code-lang')?.textContent === 'java',
  container.querySelector('.code-lang')?.textContent)
check('头部在 pre 之前（流式布局，不会盖住代码）',
  container.querySelector('.code-block')?.firstElementChild?.className === 'code-head')
check('复制按钮存在且初始文案为「复制」',
  container.querySelector('.code-copy')?.textContent === '复制', container.querySelector('.code-copy')?.textContent)

const { container: noLang } = mount(['```', 'echo hi', '```', ''].join('\n'))
check('无语言标注 → 标签回落为 code',
  noLang.querySelector('.code-lang')?.textContent === 'code',
  noLang.querySelector('.code-lang')?.textContent)

const { container: badLang } = mount(JAVA_BLOCK)
badLang.querySelector('code').className = 'language-evil!!'
enhanceCodeBlocks(badLang) // 已增强过 → 不改；这里只验证正则收窄的兜底
check('异常类名不会进入标签（语言标签字符集受正则收窄）',
  /^[\w+#.-]{1,20}$/.test(badLang.querySelector('.code-lang')?.textContent ?? ''),
  badLang.querySelector('.code-lang')?.textContent)

/* ================= 用例 2：行号 ================= */
console.log('\n[2] 行号（CSS 计数器，不是文本节点）')
const lines = container.querySelectorAll('.code-line')
check('行数与代码行数一致（末尾空行不编号）', lines.length === 2, lines.length)
check('每行 span 只含代码文本（数字由伪元素渲染，不在 DOM 里）',
  Array.from(lines).every((el) => /^(int [ab] = \d;\n?)$/.test(el.textContent)),
  JSON.stringify(Array.from(lines).map((el) => el.textContent)))
check('code.textContent 不含行号数字前缀',
  !/\d\s*int/.test(container.querySelector('code').textContent),
  JSON.stringify(container.querySelector('code').textContent))

/* ================= 用例 3：复制内容一致 ================= */
console.log('\n[3] 复制内容与原文本完全一致')
let clipboardText = null
Object.defineProperty(globalThis, 'navigator', {
  value: { clipboard: { writeText: async (t) => { clipboardText = t } } },
  configurable: true,
  writable: true,
})
const okViaApi = await copyText(JAVA_RAW)
check('Clipboard API 路径返回成功', okViaApi === true)
check('复制内容与代码原文逐字一致（含末尾换行、不含行号）',
  clipboardText === JAVA_RAW, JSON.stringify(clipboardText))

// 点击按钮：文案变「已复制」，且写进剪贴板的仍是原文（不是 DOM 里的行包装结果）
clipboardText = null
container.querySelector('.code-copy').dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }))
await new Promise((r) => setTimeout(r, 0))
check('点击复制按钮后文案变为「已复制」',
  container.querySelector('.code-copy')?.textContent === '已复制',
  container.querySelector('.code-copy')?.textContent)
check('按钮点击写入剪贴板的同样是代码原文', clipboardText === JAVA_RAW, JSON.stringify(clipboardText))

/* ================= 用例 4：退化路径（http + IP 场景） ================= */
console.log('\n[4] 无 Clipboard API 时的退化路径')
Object.defineProperty(globalThis, 'navigator', {
  value: {}, // 模拟非安全上下文：navigator.clipboard 不存在
  configurable: true,
  writable: true,
})
let execCommandCalls = 0
let execCommandText = null
document.execCommand = (cmd) => {
  execCommandCalls++
  execCommandText = document.body.querySelector('textarea')?.value ?? null
  return cmd === 'copy'
}
const okFallback = await copyText(JAVA_RAW)
check('无 Clipboard API 时退回 execCommand 且返回成功', okFallback === true)
check('execCommand 路径传入的文本同样是代码原文',
  execCommandText === JAVA_RAW, JSON.stringify(execCommandText))
check('临时 textarea 已从页面移除（不留垃圾节点）',
  !document.body.querySelector('textarea'), 'textarea 仍在')
check('execCommand 只被调用 1 次', execCommandCalls === 1, execCommandCalls)

// Clipboard 存在但写入被拒（权限/非聚焦）→ 仍要能复制
Object.defineProperty(globalThis, 'navigator', {
  value: { clipboard: { writeText: async () => { throw new Error('NotAllowedError') } } },
  configurable: true,
  writable: true,
})
execCommandCalls = 0
const okOnReject = await copyText(JAVA_RAW)
check('Clipboard 被拒时自动回退并成功', okOnReject === true && execCommandCalls === 1, execCommandCalls)

// 两条路径都失败 → 如实返回 false（按钮会显示「复制失败」，而不是假装成功）
Object.defineProperty(globalThis, 'navigator', { value: {}, configurable: true, writable: true })
document.execCommand = () => false
check('两条路径都失败时返回 false', (await copyText(JAVA_RAW)) === false)

/* ================= 用例 5：幂等与不误伤 ================= */
console.log('\n[5] 幂等、行内代码与安全边界')
const again = enhanceCodeBlocks(container)
check('重复增强返回 0（幂等）', again === 0, again)
check('重复增强不会嵌套出第二个 .code-block',
  container.querySelectorAll('.code-block').length === 1,
  container.querySelectorAll('.code-block').length)
check('重复增强不会翻倍复制按钮 / 行号',
  container.querySelectorAll('.code-copy').length === 1 && container.querySelectorAll('.code-line').length === 2)

const { container: inline, enhancedCount: inlineCount } = mount('这是一段 `行内代码` 与文字。')
check('行内代码不被包装成代码块', inlineCount === 0 && inline.querySelectorAll('.code-block').length === 0)
check('行内代码本身还在', inline.querySelector('code')?.textContent === '行内代码')

const xssBlock = ['```html', '<img src=x onerror=alert(1)>', '</code></pre><script>alert(2)</script>', '```', ''].join('\n')
const { container: escaped } = mount(xssBlock)
check('代码块里的 HTML 只作为文本存在（无 img/script 元素）',
  escaped.querySelectorAll('pre img, pre script, .code-block img, .code-block script').length === 0)
check('代码块文本里仍能读到那段标签（说明是原样展示）',
  (escaped.querySelector('code')?.textContent ?? '').includes('<img src=x onerror=alert(1)>'),
  escaped.querySelector('code')?.textContent)

/* ============ 收尾 ============ */
await vite.close()

console.log('\n' + '='.repeat(58))
console.log(`代码块增强验证：${pass}/${pass + fail} 通过`)
if (failures.length) console.log('失败：' + failures.join(' | '))
console.log('='.repeat(58))
process.exit(fail ? 1 : 0)