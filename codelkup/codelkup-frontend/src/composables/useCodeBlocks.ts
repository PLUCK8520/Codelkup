/**
 * 代码块增强（M2-13 / ADR-7）：语言标签 + 复制按钮 + 行号。
 *
 * <h2>为什么是 DOM 增强而不是改渲染管线</h2>
 * ADR-7 已经定过：本轮不引高亮库（包体积 + 与 DOMPurify 白名单的交互都要额外验证）。
 * 而"标签/复制/行号"三件事都不需要重新解析 Markdown —— 它们是对**已消毒的
 * 渲染结果**做结构性包装，所以放在渲染之后的 DOM 层做：不改 markdown.ts 的
 * 消毒链路，也就不引入任何新的 XSS 面（包装出来的节点全部是我们自己创建的，
 * 文案一律用 textContent 写入）。
 *
 * <h2>三个刻意的取舍</h2>
 * <ol>
 *   <li><b>行号用 CSS 计数器（::before）而不是把数字写成文本</b>：
 *       {@code textContent} 不含伪元素内容，所以"复制整段代码"拿到的永远不会
 *       把行号带上；同时 {@code user-select: none} 让手动框选也选不到数字。</li>
 *   <li><b>复制源用渲染时抓下来的原始文本</b>，而不是点击时再从 DOM 读：
 *       行号包装会改变 DOM 结构，从结构里反推文本是自找麻烦；
 *       而且这样"复制内容与原 Markdown 完全一致"是天然成立的，不需要额外测试保证。</li>
 *   <li><b>复制要留退化路径</b>：Clipboard API 只在安全上下文（https / localhost）可用，
 *       本项目的演示站可能用 http + IP 访问 —— 那种环境下 {@code navigator.clipboard}
 *       根本不存在，只写 API 会出现"按钮点了没反应"。</li>
 * </ol>
 *
 * @author 刘
 */

/** 语言标签允许的字符集（来自 className，属于外部输入，必须收窄） */
const LANGUAGE_PATTERN = /language-([\w+#.-]{1,20})/

/** 无语言标注时的标签 */
const DEFAULT_LABEL = 'code'

/** 已增强过标记：避免重复调用时把代码块包两层 */
const ENHANCED_FLAG = 'codeEnhanced'

/**
 * 给容器里每个代码块加语言标签、复制按钮与行号。
 *
 * <p>幂等：重复调用只会处理尚未增强的块（内容更新后 v-html 会重建 DOM，
 * 那时标记随之消失，于是自动重新增强 —— 这正是我们要的行为）。
 *
 * @returns 本次增强的代码块数量
 */
export function enhanceCodeBlocks(root: HTMLElement | null | undefined): number {
  if (!root) return 0

  const blocks = Array.from(root.querySelectorAll('pre > code')) as HTMLElement[]
  let enhanced = 0

  for (const code of blocks) {
    const pre = code.parentElement
    if (!pre || pre.dataset[ENHANCED_FLAG] === '1') continue

    // ① 先把原始文本抓下来 —— 之后 DOM 会被行号包装改写
    const raw = code.textContent ?? ''

    // ② 语言标签：从 class="language-xxx" 提取，收窄字符集后再写入 textContent
    const matched = LANGUAGE_PATTERN.exec(code.className)
    const label = matched ? matched[1].toLowerCase() : DEFAULT_LABEL

    // ③ 行号包装：按行拆 span，用 CSS 计数器显示序号
    wrapLines(code, raw)

    // ④ 头部（语言 + 复制）包在 pre 之外 —— 用普通流式布局而不是绝对定位，
    //    这样任何屏宽下都不会盖住代码（移动端验收项）
    const block = document.createElement('div')
    block.className = 'code-block'

    const head = document.createElement('div')
    head.className = 'code-head'

    const lang = document.createElement('span')
    lang.className = 'code-lang'
    lang.textContent = label

    const copyBtn = document.createElement('button')
    copyBtn.type = 'button'
    copyBtn.className = 'code-copy'
    copyBtn.textContent = '复制'
    copyBtn.setAttribute('aria-label', `复制这段 ${label} 代码`)
    copyBtn.addEventListener('click', () => {
      void copyText(raw).then((ok) => {
        copyBtn.textContent = ok ? '已复制' : '复制失败'
        copyBtn.classList.toggle('copied', ok)
        // 复位：只改文案不改尺寸，避免按钮在"已复制"与"复制"之间跳动
        window.setTimeout(() => {
          copyBtn.textContent = '复制'
          copyBtn.classList.remove('copied')
        }, 1600)
      })
    })

    head.append(lang, copyBtn)
    block.append(head)

    pre.parentNode?.insertBefore(block, pre)
    block.append(pre)
    pre.dataset[ENHANCED_FLAG] = '1'

    enhanced++
  }

  return enhanced
}

/**
 * 把代码文本按行包成 {@code <span class="code-line">}，供 CSS 计数器编号。
 *
 * <p>末尾那个空行是 marked 的产物（代码块文本总以换行结尾），它没有内容却会
 * 多出一个行号 —— 所以只用于<b>显示</b>时丢掉；复制用的仍是原始字符串。
 */
function wrapLines(code: HTMLElement, raw: string) {
  const lines = raw.split('\n')
  if (lines.length > 1 && lines[lines.length - 1] === '') {
    lines.pop()
  }

  code.textContent = ''
  for (const line of lines) {
    const span = document.createElement('span')
    span.className = 'code-line'
    // 保留换行：多行代码在同一 <pre> 里仍需逐行换行
    span.textContent = `${line}\n`
    code.append(span)
  }
}

/**
 * 复制文本到剪贴板。
 *
 * <p>优先 Clipboard API（异步、无副作用），不可用时退回
 * {@code document.execCommand('copy')} + 临时 textarea（已废弃但覆盖面最广）。
 *
 * @returns 复制成功返回 true；两条路径都失败返回 false
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 权限被拒 / 非安全上下文：继续尝试退化路径
  }

  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.setAttribute('readonly', '')
    // 固定定位 + 透明：不引起页面滚动跳动，也不闪一下白块
    textarea.style.position = 'fixed'
    textarea.style.top = '0'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch {
    return false
  }
}