/**
 * Sprint 4 · 前端验收脚本（检索高亮）
 *
 * 覆盖两件事：
 *   1. 高亮切分算法的正确性（含边界与不变量）
 *   2. **架构约束**：高亮必须走"纯文本切片 + v-for 渲染"，
 *      不得退化成拼 HTML 字符串 + v-html
 *
 * 第 2 点是本脚本的重点。功能测试只能证明"当前的用法是对的"，
 * 无法阻止后来者为了省事改成 `innerHTML` —— 而一旦那样改，
 * 用户输入的关键词就直接进入 HTML 解析，XSS 立刻成立。
 * 所以这里直接读源码做结构性断言，把约定固化成测试。
 *
 * 运行：
 *   node verify-sprint4-frontend.mjs
 * 退出码：0 = 全通过，1 = 有失败用例。
 */
import { readFileSync } from 'node:fs'
import { createServer } from './node_modules/vite/dist/node/index.js'

/* ============================ 1. 加载真实源码 ============================ */
const server = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
})
const { splitByKeywords, hasHit } = await server.ssrLoadModule('/src/utils/highlight.ts')

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

/* ============================ 2. 切分正确性 ============================ */
console.log('\n--- 切分正确性 ---\n')

/**
 * [名称, 文本, 关键词, 期望的命中片段数组]
 * 断言命中片段（hit=true）的文本序列与期望一致。
 */
const cases = [
  ['命中在中间', '我是 Redis 缓存', 'Redis', ['Redis']],
  ['命中在开头', 'Redis 是内存数据库', 'Redis', ['Redis']],
  ['命中在结尾', '这里讲的是 Redis', 'Redis', ['Redis']],
  ['多次出现全部命中', 'Redis 与 redis 都算', 'redis', ['Redis', 'redis']],
  ['中文关键词', '虚拟线程在 IO 密集场景的实测', '虚拟线程', ['虚拟线程']],
  ['中文多次出现', '线程池的线程复用', '线程', ['线程', '线程']],
  ['多词都命中', 'Redis 计数与线程安全', 'Redis 线程', ['Redis', '线程']],
  ['大小写不敏感（小写查大写）', 'Redis 缓存', 'redis', ['Redis']],
  ['大小写不敏感（大写查小写）', 'redis 缓存', 'REDIS', ['redis']],
  ['正则元字符不作通配', 'aXb 与 a.b', 'a.b', ['a.b']],
]

for (const [name, text, kw, expected] of cases) {
  const segs = splitByKeywords(text, kw)
  const hits = segs.filter((s) => s.hit).map((s) => s.text)
  check(name, JSON.stringify(hits) === JSON.stringify(expected), `实际命中=${JSON.stringify(hits)}`)
}

/* ============================ 3. 不变量：拼接必须等于原文 ============================ */
console.log('\n--- 不变量（切分不能丢字/加字）---\n')

/**
 * 这是最有价值的一条断言：无论关键词多刁钻，
 * 所有片段按序拼接后必须**逐字节等于**原文本。
 * 若实现里混入了任何标签或标记，这里立刻会失败 ——
 * 它等价于"高亮逻辑没有向文本里注入任何东西"。
 */
const invariantCases = [
  ['普通中文', '虚拟线程在 IO 密集场景的实测', '线程'],
  ['含 HTML 尖括号', '<script>alert(1)</script> 讲的是脚本注入', 'script'],
  ['含引号与等号', '<img src=x onerror=alert(1)>', 'onerror'],
  ['含反斜杠与括号', 'regex (\\d+)* 的写法', '('],
  ['含方括号', '数组 a[0] 与 b[1]', '['],
  ['含美元符号', '代价是 $100 与 $200', '$'],
  ['含加号', 'C++ 与 C# 的区别', '++'],
  ['含中文标点', '「引号」、顿号、破折号——都有', '顿号'],
  ['重复词组', 'abcabcabc', 'abc'],
  ['长文本', 'x'.repeat(500) + 'needle' + 'y'.repeat(500), 'needle'],
  ['emoji', '用 🚀 表示部署，🚀 也可以表示发布', '🚀'],
]

for (const [name, text, kw] of invariantCases) {
  const segs = splitByKeywords(text, kw)
  const joined = segs.map((s) => s.text).join('')
  check(`拼接完整：${name}`, joined === text,
    `len ${joined.length} vs ${text.length}`)
}

/* ============================ 4. 边界与不抛异常 ============================ */
console.log('\n--- 边界与健壮性 ---\n')

check('空文本返回空数组', JSON.stringify(splitByKeywords('', 'x')) === '[]')
check('null 文本返回空数组', JSON.stringify(splitByKeywords(null, 'x')) === '[]')
check('undefined 文本返回空数组', JSON.stringify(splitByKeywords(undefined, 'x')) === '[]')

check('空关键词 → 单段且不命中',
  (() => { const s = splitByKeywords('abc', ''); return s.length === 1 && !s[0].hit })())
check('null 关键词 → 单段且不命中',
  (() => { const s = splitByKeywords('abc', null); return s.length === 1 && !s[0].hit })())
check('纯空白关键词 → 单段且不命中',
  (() => { const s = splitByKeywords('abc', '   '); return s.length === 1 && !s[0].hit })())

check('无命中 → 单段且不命中',
  (() => { const s = splitByKeywords('abc', 'zzz'); return s.length === 1 && !s[0].hit })())

// 各种"看起来像正则/HTML"的关键词都必须安全返回而不抛异常
const hostileKeywords = [
  '(', ')', '[', ']', '{', '}', '*', '+', '?', '^', '$', '|', '\\',
  '.*', '.+', '(?:', 'a|b', '\\d+',
  '<script>', '</script>', '<img src=x onerror=alert(1)>',
  '&lt;script&gt;', '&#x3c;script&#x3e;', 'javascript:alert(1)',
  'NaN', 'null', 'constructor', '__proto__',
]
let hostileOk = true
let hostileBad = ''
for (const kw of hostileKeywords) {
  try {
    const segs = splitByKeywords('一些正常文本<script>alert(1)</script>结束', kw)
    if (segs.map((s) => s.text).join('') !== '一些正常文本<script>alert(1)</script>结束') {
      hostileOk = false
      hostileBad = `拼接不一致 @ ${kw}`
      break
    }
  } catch (e) {
    hostileOk = false
    hostileBad = `${kw} 抛异常: ${e.message}`
    break
  }
}
check('恶意/元字符关键词不抛异常且不破坏文本', hostileOk, hostileBad)

/* ============================ 5. hasHit ============================ */
console.log('\n--- hasHit ---\n')

check('hasHit 命中为 true', hasHit('我是 Redis', 'redis') === true)
check('hasHit 未命中为 false', hasHit('我是 MySQL', 'redis') === false)
check('hasHit 空文本为 false', hasHit('', 'x') === false)

/* ============================ 6. 架构约束（源码级）============================ */
console.log('\n--- 架构约束：禁止 v-html 拼接高亮 ---\n')

/**
 * 这几条断言不是"功能对不对"，而是"实现方式必须保持安全"。
 * 高亮一旦改成拼 HTML + v-html，用户输入的关键词就进入 HTML 解析器，
 * XSS 立刻成立。用测试把这条约定钉住，比写在文档里可靠。
 */
function readSrc(relative) {
  try {
    return readFileSync(new URL(relative, import.meta.url), 'utf8')
  } catch {
    return null
  }
}

/**
 * 剥离注释后再做结构断言。
 *
 * ⚠️ 这一步不能省：这些文件的注释里大量提到 "v-html"（正是在解释
 *    "为什么不用它"）。直接对整个源码跑 /v-html/ 会把说明文字也算作违规，
 *    产生假阳性 —— 第一次跑就踩到了这个坑。
 *    断言必须针对"实际代码"，而不是"任何出现过的字面量"。
 *
 * 注：只处理整行 `//` 注释，不处理行尾 `//`，避免误伤 URL 里的 `//`。
 */
function stripComments(src) {
  if (src === null) return null
  return src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
}

const highlightSrc = stripComments(readSrc('./src/utils/highlight.ts'))
check('highlight.ts 不含 innerHTML / outerHTML',
  highlightSrc !== null && !/innerHTML|outerHTML/.test(highlightSrc),
  '源码中出现了 innerHTML')

const hlCompSrc = stripComments(readSrc('./src/components/HighlightText.vue'))
check('HighlightText.vue 不含 v-html（已剥离注释）',
  hlCompSrc !== null && !/v-html/.test(hlCompSrc),
  '组件里使用了 v-html')
check('HighlightText.vue 用 v-for 渲染片段',
  hlCompSrc !== null && /v-for/.test(hlCompSrc) && /mark/.test(hlCompSrc),
  '未找到 v-for / mark')

const postCardSrc = stripComments(readSrc('./src/components/PostCard.vue'))
check('PostCard.vue 不含 v-html（已剥离注释）',
  postCardSrc !== null && !/v-html/.test(postCardSrc),
  'PostCard 里出现了 v-html')
check('PostCard.vue 使用 HighlightText 组件',
  postCardSrc !== null && /HighlightText/.test(postCardSrc),
  '未引用 HighlightText')

// 反向约束：搜索相关页面也不该偷偷用 v-html 渲染 snippet
const searchViewSrc = stripComments(readSrc('./src/views/search/SearchView.vue'))
check('SearchView.vue 不含 v-html', searchViewSrc !== null && !/v-html/.test(searchViewSrc),
  'SearchView 里出现了 v-html')

/* ============================ 7. 汇总 ============================ */
await server.close()

console.log('\n' + '='.repeat(58))
console.log(`Sprint4 前端验收 · 检索高亮切分`)
console.log(`用例总数 ${pass + fail}  |  PASS=${pass}  FAIL=${fail}`)
console.log('='.repeat(58))
if (fail > 0) {
  console.log('失败用例：')
  failures.forEach((n) => console.log('  - ' + n))
}

process.exit(fail === 0 ? 0 : 1)
