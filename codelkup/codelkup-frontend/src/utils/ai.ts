/**
 * AI 输出的展示工具（M3-8）。
 *
 * <h2>为什么答案要"分段"而不是直接渲染</h2>
 * 后端返回的答案是**纯文本**（D4：后端不产生 HTML）。要把它渲染成
 * "正文 + 可点击的引用角标"，只有两条路：
 * <ol>
 *   <li>把 `[1]` 替换成 `<a>` 再用 v-html 塞进去 —— 一旦答案里带 HTML，
 *       就等于把 XSS 面重新引回来；</li>
 *   <li>把答案**切成结构化数组**（文本段 / 引用段），模板里 v-for 渲染 ——
 *       全程没有 innerHTML。</li>
 * </ol>
 * 这里选第二条：解析结果是数据而不是字符串，XSS 面在这一层直接不存在。
 *
 * @author 刘
 */

export type AnswerSegment =
  | { type: 'text'; text: string }
  | { type: 'cite'; index: number }

/** 与后端一致的引用标记：`[1]` ~ `[99]`（后端用 \d{1,2}） */
const CITATION_PATTERN = /\[(\d{1,2})]/g

/**
 * 把答案切成"文本段 / 引用段"。
 *
 * <p>引用段只带**编号**（1 起），不在这里查 citations 数组 —— 编号越界时
 * 由调用方降级成纯文本（后端也会过滤越界编号，这里是双保险）。
 */
export function parseAnswerSegments(answer?: string | null): AnswerSegment[] {
  const text = answer ?? ''
  const segments: AnswerSegment[] = []
  let cursor = 0

  for (const match of text.matchAll(CITATION_PATTERN)) {
    const at = match.index ?? 0
    if (at > cursor) {
      segments.push({ type: 'text', text: text.slice(cursor, at) })
    }
    segments.push({ type: 'cite', index: Number(match[1]) })
    cursor = at + match[0].length
  }
  if (cursor < text.length) {
    segments.push({ type: 'text', text: text.slice(cursor) })
  }
  return segments
}

/**
 * 降级原因 → 一句给用户看的话。
 *
 * <p>没有用后端返回的提示文案（`answer` 里那句）：那句话说给"带检索结果的降级"场景、
 * 较长；摘要的降级没有 answer 字段。这里按原因给一句短话，两处 UI 共用同一份口径。
 */
export function aiDegradeText(reason?: string | null): string {
  switch (reason) {
    case 'NO_KEY':
    case 'DISABLED':
      return 'AI 暂未启用（未配置模型密钥）'
    case 'BUDGET':
      return '今日 AI 额度已用完，请明天再试'
    case 'TIMEOUT':
      return 'AI 响应超时，请稍后再试'
    case 'NO_EVIDENCE':
      return '站内没有找到相关内容'
    default:
      return 'AI 暂时不可用'
  }
}