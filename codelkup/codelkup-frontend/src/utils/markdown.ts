import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * Markdown 渲染 + XSS 消毒。
 *
 * ⚠️ 这是 DEV-DOC §13.4 的**硬性要求**，也是把 content_html 的存储风险
 *    （修订 D4）挪到前端后的关键补偿措施。渲染链路：
 *
 *      用户输入 Markdown → 后端原样存库 → 前端 marked 渲染 → DOMPurify 消毒 → v-html
 *
 * 为什么消毒必须在**渲染之后**：
 *   Markdown 允许内联 HTML（`<img onerror=...>`、`<script>`、`<iframe>`）。
 *   若只过滤 Markdown 源码，攻击者可绕过（例如 markdown 语法组合出的标签）。
 *   正确做法是让 marked 先产出完整 HTML，再对这个 HTML 做白名单消毒。
 *
 * ⚠️ 千万不要把消毒结果直接丢进 v-html 之外的地方，也不要省掉 DOMPurify
 *    「反正 marked 会转义」—— marked 默认**不**转义内联 HTML。
 */

/** 允许的标签：只放开发帖会真正用到的语义标签 + 代码高亮所需 */
const ALLOWED_TAGS = [
  'p', 'br', 'hr', 'strong', 'em', 'del', 's', 'u', 'sub', 'sup',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'ul', 'ol', 'li',
  'blockquote', 'pre', 'code',
  'a', 'img',
  'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'span', 'div', // 代码高亮库会在这些标签上挂 class
]

/** 允许的属性：a 的 href/title、img 的 src/alt、代码高亮的 class */
const ALLOWED_ATTR = ['href', 'title', 'target', 'rel', 'src', 'alt', 'class', 'align']

/**
 * 允许的协议：挡住 javascript: / data: 这类可执行伪协议。
 *
 * ⚠️ **不要把防线放在这里。** 实测（DOMPurify 3.4.16）该选项的行为有两个坑：
 *
 *   1. **`data:` 有特例放行**：`img` 在 DOMPurify 内部的 `DATA_URI_TAGS` 里，
 *      凡是 `data:` 开头的 `img src` 会**绕过这个正则**直接保留。
 *      实测 `data:text/html;base64,...` 与 `data:image/svg+xml;base64,...`
 *      无论正则怎么写都能穿过去（SVG 内可嵌 script）。
 *   2. **正则过严会误杀相对路径**：本项要求整串匹配，`./a.png` / `a.png` / `foo/bar.png`
 *      都不以 `https?:` `mailto:` `tel:` `#` `/` 开头 → 直接被删。
 *
 *   另外它**拦不住协议相对 URL**：`//evil.com/x.png` 两边都放行。
 *
 * 所以这里给一个**宽松的"不误杀"正则**（只拦明显可执行的伪协议），
 * 真正的 URI 白名单在下方 isSafeUri() + afterSanitizeAttributes hook 里做，
 * 那里能同时覆盖 data: 特例与协议相对 URL。
 */
const ALLOWED_URI_REGEXP = /^(?:(?:https?|mailto|tel|ftp):|#|\/|\.{1,2}\/|[^a-z0-9+.-]|[a-z0-9+.-]*[^a-z0-9+.:-][^:]*$)/i

/**
 * 链接允许的协议：http(s) / mailto / tel / 站内锚点 / 站内相对路径。
 * 相对路径只放行 `/`（根路径）与 `./` `../`，不放行裸 `foo:` 形式。
 * ⚠️ `/(?!\/)` 用来拦住协议相对 URL `//evil.com`。
 */
const SAFE_LINK_URI = /^(?:https?:\/\/|mailto:|tel:|#|\/(?!\/)|\.{1,2}\/)/i

/**
 * 图片允许的协议：只允许 http(s) 与站内路径。
 * 明确排除 `data:` —— data URI 可承载 `image/svg+xml`，而 SVG 内可嵌 script，
 * 一旦渲染链路被改动（例如允许内联 svg），它就会变成真实的执行面。
 * 排除 `blob:` / `file:` / `javascript:` / 协议相对 URL `//` 同理。
 */
const SAFE_IMAGE_URI = /^(?:https?:\/\/|\/(?!\/)|\.{1,2}\/)/i

/**
 * 把属性值还原成"用于协议判定的形态"。
 *
 * 攻击者会用两层混淆绕过朴素正则：
 *   1. HTML 实体：`&#106;avascript:` / `&colon;` / `&#x6A;avascript:` → 需解码
 *   2. 控制字符：`java\tscript:` / `java\nscript:` → 需剥离
 * 解码 + 剥离空白与控制字符之后再比对协议白名单。
 *
 * ⚠️ 剥离空白是**有代价**的：`  javascript:`（前导空格）会被归一成
 *    `javascript:`，正好落进黑名单 —— 这是想要的效果。
 *    但相对的，路径里的空白也会被抹掉，只是 URL 中本就该用 `%20`，
 *    裸空格在 src/href 里属于非法输入，抹掉不影响正常使用。
 *
 * 注：`&#106;` 这类**不带分号**的实体在 HTML 规范里是允许的（数字实体），
 *    这里用 `;?` 一并覆盖；命名实体（如 `&colon;`）则必须有分号。
 */
function normalizeUri(value: string): string {
  const decoded = value
    .replace(/&#x([0-9a-f]+);?/gi, (_, hex) => codePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);?/g, (_, dec) => codePoint(parseInt(dec, 10)))
    .replace(/&(amp|lt|gt|quot|apos|colon|tab|newline);/gi, (_, name) => {
      const named: Record<string, string> = {
        amp: '&', lt: '<', gt: '>', quot: '"', apos: "'",
        colon: ':', tab: '\t', newline: '\n',
      }
      return named[name.toLowerCase()] ?? `&${name};`
    })
  // 协议判定前剥离所有空白与控制字符："java\tscript:" 与 "javascript:" 等价
  const stripped = decoded.replace(/[\u0000-\u0020\u007f]+/g, '')
  // ⚠️ 反斜杠必须归一成斜杠再做协议判定：
  //    浏览器按 URL 规范（special scheme）把 `\` 当 `/`，所以 `/\evil.com`
  //    会被解析成 `//evil.com`（协议相对 URL）。若这里不归一，
  //    `\/(?!\/)` 只看到"单个斜杠 + 反斜杠"，会当成站内路径放行 —— 点击即跳外站，
  //    图片则是每个访客的浏览器都向攻击者服务器发一次请求。
  return stripped.replace(/\\/g, '/')
}

function codePoint(n: number): string {
  if (!Number.isFinite(n) || n < 0 || n > 0x10ffff) return ''
  try {
    return String.fromCodePoint(n)
  } catch {
    return ''
  }
}

/**
 * 校验属性值里的 URL 协议是否在白名单内。
 *
 * - 空值视为合法（`<a href="">` 无危害），由调用方自行决定是否移除；
 * - 判定前先 normalizeUri 解码实体、剥离空白，防止混淆绕过；
 * - `pattern` 一律带 `^.../i` 锚定开头并忽略大小写，非绝对匹配。
 */
function isSafeUri(value: string, pattern: RegExp): boolean {
  const normalized = normalizeUri(value)
  if (!normalized) return true
  return pattern.test(normalized)
}

// marked 全局配置：开启 GFM（表格、删除线、任务列表）
marked.setOptions({
  gfm: true,
  breaks: true, // 单个换行也换行，符合聊天/论坛的输入直觉
})

/**
 * 渲染 Markdown 为**已消毒**的 HTML。
 *
 * 返回的字符串可直接用于 v-html —— 因为已经过白名单消毒。
 */
export function renderMarkdown(raw: string | null | undefined): string {
  if (!raw) return ''

  // 1. marked 解析（可能抛错：畸形输入不该让整个页面崩）
  let html: string
  try {
    html = marked.parse(raw, { async: false }) as string
  } catch {
    // 解析失败就退化成纯文本展示，转义后再包 pre
    return `<pre>${escapeHtml(raw)}</pre>`
  }

  // 2. DOMPurify 消毒 —— 白名单模式，未列出的一律剔除
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS,
    ALLOWED_ATTR,
    ALLOWED_URI_REGEXP,
    // 明确禁止这些危险标签（即使有人后来把上面白名单改宽了，这里也是兜底）
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form', 'input', 'link', 'meta'],
    FORBID_ATTR: ['onerror', 'onload', 'onclick', 'onmouseover', 'onfocus', 'style'],
    // 不允许 data: URI（可用于 SVG 内嵌脚本）
    ALLOW_DATA_ATTR: false,
  })
}

/**
 * 消毒后做两件事：
 *   1. **协议白名单校验**（真正的 XSS 防线，见 ALLOWED_URI_REGEXP 上方注释）
 *   2. 给外链补 rel / target，给图片补懒加载
 *
 * ⚠️ 这里必须用 `getAttribute()` 而不是 `node.href` / `node.src`：
 *    后者返回的是**已解析的绝对 URL**，会把 `./a.png` 变成 `http://host/a.png`，
 *    也会把 `//evil.com/x.png` 变成 `http://evil.com/x.png`，协议判定就失真了。
 *
 * ⚠️ 顺序很重要：先做协议校验并剔除非法值，再做属性补充。
 *    否则会给一个即将被剥掉 href 的 <a> 先加上 target="_blank"。
 */
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  const tag = node.tagName

  /* --- 1. 协议白名单：补齐 DOMPurify 的 data: 特例与协议相对 URL 漏洞 --- */
  if (tag === 'A') {
    const href = node.getAttribute('href')
    if (href !== null && !isSafeUri(href, SAFE_LINK_URI)) {
      node.removeAttribute('href')
      // 同时清掉可能由 Markdown 语法带进来的 target/rel，
      // 避免留下"可点但无地址"的误导性链接
      node.removeAttribute('target')
      node.removeAttribute('rel')
    }
  }
  if (tag === 'IMG') {
    const src = node.getAttribute('src')
    if (src === null || !isSafeUri(src, SAFE_IMAGE_URI)) {
      // 非法来源的图片直接整个摘掉：留着空 src 或 alt 会渲染成裂图/占位，不如不留
      node.remove()
      return
    }
  }

  /* --- 2. 外链安全属性：先统一清掉 target，再只为真正的外链补上 ---
     非 http(s) 链接（mailto: / #锚点 / 站内相对路径）保留 target 没有意义
     （浏览器只对可导航的外链生效），而 Markdown 内嵌 HTML 可以自带 target，
     统一"先清后补"杜绝残留（安全审计 informational 项）。 */
  if (tag === 'A') {
    node.removeAttribute('target')
    const href = node.getAttribute('href') || ''
    if (/^https?:\/\//i.test(href)) {
      node.setAttribute('target', '_blank')
      node.setAttribute('rel', 'noopener noreferrer nofollow')
    }
  }

  /* --- 3. 图片懒加载 --- */
  if (tag === 'IMG') {
    node.setAttribute('loading', 'lazy')
    node.setAttribute('decoding', 'async')
  }
})

function escapeHtml(text: string): string {
  const map: Record<string, string> = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  }
  return text.replace(/[&<>"']/g, (ch) => map[ch])
}
