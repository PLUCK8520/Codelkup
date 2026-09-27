/**
 * 前端验收脚本（AI 面板）：钉死"AI 输出怎么变成界面"的几条契约。
 *
 * <h2>为什么这几条值得单独钉</h2>
 * AI 面板是**唯一一处把非本站内容渲染给用户的地方**（模型输出 + 站内用户片段），
 * 所以它的正确性由两件事决定：
 *   1. **渲染路径**：必须"结构化分段 → 模板渲染"，绝不能把文本拼成 HTML 再塞进去
 *      （一旦拼 HTML，站内投毒就能顺着 AI 的回答把脚本带进读者浏览器）；
 *   2. **降级可见性**：未配密钥 / 超预算 / 上游失败必须变成一句人话，
 *      而不是报错、更不是"假装有摘要"。
 *
 * 第 1 条的正确性全在**解析规则**上（引用编号怎么切、越界怎么办、不完整标记怎么办），
 * 所以这里跑真源码（vite.ssrLoadModule）而不是读字符串；
 * 第 1 条的"没有 v-html"与第 2 条的静态部分用源码断言 --
 * 这类"边界不存在"的结论只能靠静态检查。
 *
 * 真实 HTTP 路径与浏览器表现由 verify-v03-ai.py（stub 冒充上游）与浏览器实测覆盖。
 *
 * 运行：node verify-v03-ai-frontend.mjs
 * 退出码：0 = 全通过，1 = 有失败
 */
import { readFileSync } from 'node:fs'
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

const vite = await createViteServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
})
const { parseAnswerSegments, aiDegradeText } = await vite.ssrLoadModule('/src/utils/ai.ts')

const types = (segs) => segs.map((s) => s.type).join(',')

/* ================= [A] 答案分段（渲染路径的核心规则） ================= */
console.log('\n[A] 答案分段：文本与引用角标怎么切')

check('无引用的答案 → 单个文本段', types(parseAnswerSegments('虚拟线程更适合 IO 密集场景。')) === 'text')
check('单个引用 → 文本 + 引用',
  types(parseAnswerSegments('虚拟线程更适合 IO 密集场景 [1]。')) === 'text,cite,text')
check('引用编号被正确解析（1 起）',
  parseAnswerSegments('答案 [2]')[1].index === 2)
check('多个引用保持出现顺序',
  JSON.stringify(parseAnswerSegments('[2] 先说二 [1] 再说一')
    .filter((s) => s.type === 'cite').map((s) => s.index)) === '[2,1]')
check('答案以引用开头 → 不产生空文本段',
  types(parseAnswerSegments('[1] 开头就是引用')) === 'cite,text')
check('答案以引用结尾 → 不产生空文本段',
  types(parseAnswerSegments('结尾是引用 [1]')) === 'text,cite')
check('越界编号仍切成引用段（留给调用方降级成纯文本，不在这里猜 citations）',
  types(parseAnswerSegments('依据 [9]')) === 'text,cite')
check('超过两位的编号（[100]）不当作引用 → 纯文本',
  types(parseAnswerSegments('这是 [100] 不是引用')) === 'text')
check('不完整标记（只有左括号）不切分',
  types(parseAnswerSegments('半截 [1 标记')) === 'text')
check('换行保留在文本段里（面板按 pre-wrap 渲染）',
  parseAnswerSegments('第一行\n第二行 [1]')[0].text === '第一行\n第二行 ')
check('null / 空串 / undefined → 空数组（不抛异常）',
  parseAnswerSegments(null).length === 0 && parseAnswerSegments('').length === 0
  && parseAnswerSegments(undefined).length === 0)
check('纯引用答案（只有一个编号）也不丢内容',
  types(parseAnswerSegments('[1]')) === 'cite')

/* ================= [B] 降级文案 ================= */
console.log('\n[B] 降级文案：每种原因都要有一句人话')

check('NO_KEY → 提示未配置密钥（而不是"错误"）',
  aiDegradeText('NO_KEY').includes('未配置模型密钥'))
check('DISABLED 与 NO_KEY 同一口径', aiDegradeText('DISABLED') === aiDegradeText('NO_KEY'))
check('BUDGET → 提到额度', aiDegradeText('BUDGET').includes('额度'))
check('TIMEOUT → 提到超时并建议稍后', aiDegradeText('TIMEOUT').includes('超时'))
check('NO_EVIDENCE → 说"没找到"而不是"失败"', aiDegradeText('NO_EVIDENCE').includes('没有找到'))
check('未知原因 / 空值 → 兜底为"AI 暂时不可用"',
  aiDegradeText('WHATEVER') === 'AI 暂时不可用' && aiDegradeText(null) === 'AI 暂时不可用'
  && aiDegradeText(undefined) === 'AI 暂时不可用')
check('所有降级文案都不含"错误/error"字样（降级不是报错）',
  ['NO_KEY', 'DISABLED', 'BUDGET', 'TIMEOUT', 'NO_EVIDENCE', null]
    .every((r) => !/错误|error/i.test(aiDegradeText(r))))

/* ================= [C] XSS 边界（静态断言） ================= */
console.log('\n[C] 渲染边界：AI 相关组件不得拼 HTML')

const panel = readFileSync('./src/components/AiAnswerPanel.vue', 'utf8')
const tldr = readFileSync('./src/components/PostTldr.vue', 'utf8')

/**
 * 先剥注释再检查：这两个组件的注释里**故意**写了"不走 v-html"来说明口径，
 * 直接搜字符串会把说明文字当成违规（第一版就是这么误报的）。
 * 剥掉 HTML 注释与 JS 注释后剩下的，才是真正会被执行的部分。
 */
const stripComments = (text) =>
  text
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')

const panelCode = stripComments(panel)
const tldrCode = stripComments(tldr)

check('AiAnswerPanel 实际代码不含 v-html', !panelCode.includes('v-html'))
check('AiAnswerPanel 实际代码不含 innerHTML', !panelCode.includes('innerHTML'))
check('PostTldr 实际代码不含 v-html', !tldrCode.includes('v-html'))
check('PostTldr 实际代码不含 innerHTML', !tldrCode.includes('innerHTML'))
check('AiAnswerPanel 走结构化分段（用了 parseAnswerSegments）',
  panel.includes('parseAnswerSegments'))
check('答案正文是文本插值渲染（{{ seg.text }}）', panel.includes('{{ seg.text }}'))
check('引用角标点击走路由跳转而不是整页导航',
  panel.includes("name: 'post-detail'") && panel.includes('@click.prevent'))
check('真失败态不猜原因、只给重试出口（原因由拦截器提示）',
  panel.includes('暂时没能拿到回答') && panel.includes('重试'))
check('降级时不再叠一句"没有相关讨论"（说明已在降级文案里）',
  panel.includes('!citations.length && !result.degraded'))
check('PostTldr 的降级文案来自 aiDegradeText（口径只有一份）',
  tldr.includes('aiDegradeText'))

/* ================= [D] 挂载落点（别做成"只有手改 URL 才用得上"） ================= */
console.log('\n[D] 落点：三个入口都接在真实页面上')

const layout = readFileSync('./src/layouts/AppLayout.vue', 'utf8')
const detail = readFileSync('./src/views/post/PostDetailView.vue', 'utf8')
const editor = readFileSync('./src/views/post/PostEditorView.vue', 'utf8')
const aiApiSrc = readFileSync('./src/api/ai.ts', 'utf8')

check('问答抽屉挂在布局层（任意页面可用）', layout.includes('<AiAnswerPanel v-model="askOpen" />'))
check('顶栏有「问 Codelkup」入口', layout.includes('ask-ai-btn') && layout.includes('问 Codelkup'))
check('未登录点入口 → 引导登录并带 redirect',
  layout.includes("name: 'login'") && layout.includes('redirect'))
check('AI 摘要挂在详情页正文之前（先看要点再读全文）',
  detail.includes('<PostTldr :post-id="postId" />'))
check('编辑器接了相似问题提示（M3-5 的接口终于有真实落点）',
  editor.includes('aiApi.similarQuestions'))
check('相似提示是静默请求（失败=没有提示，不在写作时弹窗）',
  aiApiSrc.includes('silent: true'))
check('相似提示只在新建稿问（编辑稿问出来的一定是它自己）',
  editor.includes('if (isEdit.value) return'))
check('去抖 + 同文本不重复问（额度花在新内容上）',
  editor.includes('lastAskedTitle') && editor.includes('800'))
check('点提示开新标签页（当前页的草稿一个字都不能动）',
  editor.includes('target="_blank"') && editor.includes('rel="noopener"'))

await vite.close()

console.log('\n' + '='.repeat(58))
console.log(`AI 面板前端契约：${pass}/${pass + fail} 通过`)
if (failures.length) console.log('失败：' + failures.join(' | '))
console.log('='.repeat(58))
process.exit(fail ? 1 : 0)