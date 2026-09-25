/**
 * 关键词高亮切分工具。
 *
 * <h2>为什么返回「片段数组」而不是 HTML 字符串</h2>
 * 最直觉的做法是拼一个 `xxx<mark>关键词</mark>yyy` 的字符串，再交给 `v-html` 渲染。
 * 这条路<b>不能走</b>，理由有两条：
 *
 * <ol>
 *   <li><b>XSS 风险</b> —— `v-html` 会把字符串当 HTML 解析。关键词来自用户输入，
 *       若用户搜的是 `&lt;img src=x onerror=alert(1)&gt;`，高亮逻辑把它拼进 HTML
 *       就等于执行了用户的脚本。虽然可以采用"先转义再插标签"的写法，
 *       但那需要正确转义文本的<b>每一个片段</b>，任何一处漏掉都是一个 XSS。
 *       靠"记得转义"维持的安全性，迟早会破。</li>
 *   <li><b>与全站约定一致</b> —— DevDoc 修订 D4 定下的规矩是"后端不产生 HTML、
 *       前端不信任存储层的 HTML"。整个项目里唯一使用 `v-html` 的地方是
 *       Markdown 渲染（且必须过 DOMPurify）。搜索高亮没有必要再开第二个口子。</li>
 * </ol>
 *
 * <p>所以这里只做<b>纯计算</b>：把文本切成 `[{text, hit}]` 的有序片段，
 * 由 `HighlightText.vue` 用 `v-for` + `&lt;mark&gt;` 渲染。Vue 的插值语法
 * 天生对文本转义，无论关键词里含什么字符都不可能变成标签 ——
 * 安全性由框架保证，而不是由"我记得转义了"保证。
 *
 * @author 刘
 */

/** 一段文本及其是否命中关键词 */
export interface HighlightSegment {
  text: string
  hit: boolean
}

/**
 * 把文本按关键词切成片段。
 *
 * @param text    原始文本（纯文本，不是 Markdown 源码）
 * @param keyword 用户输入的查询词，可含空格（多词）
 * @returns 有序片段数组；无关键词或无命中时返回单元素数组（整段 hit=false）
 */
export function splitByKeywords(text: string | undefined | null, keyword: string | undefined | null): HighlightSegment[] {
  if (!text) {
    return []
  }
  const words = (keyword ?? '')
    .split(/\s+/)
    .map((w) => w.trim())
    .filter((w) => w.length > 0)

  if (words.length === 0) {
    return [{ text, hit: false }]
  }

  // ⚠️ 大小写不敏感匹配用 toLowerCase 实现，但**必须校验长度是否变化**。
  //    某些 Unicode 字符小写化后长度会变（如 'İ'.toLowerCase() 是 2 个码元），
  //    此时用 lower 串的下标去切原串会切错位置甚至越界。
  //    长度不一致就退化为大小写敏感匹配 —— 对中文场景无影响，
  //    但能保证任何输入下都不会出现"高亮标错位置"这种诡异的错。
  const lowered = text.toLowerCase()
  const caseInsensitive = lowered.length === text.length
  const haystack = caseInsensitive ? lowered : text

  // ---- 收集所有命中区间 ----
  const ranges: Array<[number, number]> = []
  for (const word of words) {
    const needle = caseInsensitive ? word.toLowerCase() : word
    if (needle.length === 0) {
      continue
    }
    // 用 indexOf 而非正则：关键词可能含 `.` `*` `(` 等正则元字符，
    // 直接构造 RegExp 需要转义（又一个容易被遗漏的坑），indexOf 天然免疫。
    let from = 0
    for (;;) {
      const idx = haystack.indexOf(needle, from)
      if (idx === -1) {
        break
      }
      ranges.push([idx, idx + needle.length])
      // 从匹配结束处继续找，避免同一位置无限循环（needle 非空时必然前进）
      from = idx + needle.length
    }
  }

  if (ranges.length === 0) {
    return [{ text, hit: false }]
  }

  // ---- 排序并合并重叠区间 ----
  // 多词查询时区间可能重叠（如搜 "ab bc" 命中 "abc" 会产生 [0,2] 和 [1,3]），
  // 不合并会生成交错的片段，渲染出来是重复的 <mark>。
  ranges.sort((a, b) => a[0] - b[0])
  const merged: Array<[number, number]> = []
  for (const [start, end] of ranges) {
    const last = merged[merged.length - 1]
    if (last && start <= last[1]) {
      last[1] = Math.max(last[1], end)
    } else {
      merged.push([start, end])
    }
  }

  // ---- 按合并后的区间切片 ----
  const segments: HighlightSegment[] = []
  let cursor = 0
  for (const [start, end] of merged) {
    if (start > cursor) {
      segments.push({ text: text.slice(cursor, start), hit: false })
    }
    segments.push({ text: text.slice(start, end), hit: true })
    cursor = end
  }
  if (cursor < text.length) {
    segments.push({ text: text.slice(cursor), hit: false })
  }
  return segments
}

/**
 * 文本是否命中了关键词（用于决定要不要展示 snippet 而不是 summary）。
 */
export function hasHit(text: string | undefined | null, keyword: string | undefined | null): boolean {
  return splitByKeywords(text, keyword).some((s) => s.hit)
}
