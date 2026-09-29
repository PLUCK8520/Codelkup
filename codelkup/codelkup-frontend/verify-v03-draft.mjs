/**
 * 前端验收脚本（草稿自动保存）：钉死"输入 → 刷新 → 内容还在"与"发布后不留残渣"。
 *
 * M4-15 起草稿升级为"本地 + 云端"双层，脚本相应扩写两块：
 *   [C] 本地 / 云端并存时"恢复哪一份"的判定规则（pickNewerDraft）；
 *   [D] 云端链路的读写时机：节流推送、指纹去重、失败不打扰用户、清除要两侧一起清。
 *   [E] 恢复询问的会话态（本轮）：同一版草稿在本会话只问一次（kept / restored 两种表态、
 *       指纹含 updatedAt、scope 隔离、存储不可用降级）。
 * 云端通道用假 remote 注入（不依赖后端运行）——纯逻辑层测规则与时序，
 * 真实 HTTP 路径由 verify-sprint2.py 的 S2.6 与浏览器实测覆盖。
 *
 * 为什么必须跑真实模块而不是读源码字符串：草稿链路的正确性全在**时序**上 ——
 *   1. 初始回填（服务端内容进表单）不能算作"用户输入"，
 *      否则进编辑页就会把上一次的草稿覆盖掉；
 *   2. 连续输入要节流（5 秒一次），但"刷新/离开"必须立刻落盘，
 *      否则最后几秒的输入会丢；
 *   3. 版本不符/结构损坏的旧数据必须被丢弃而不是"尽力解析"。
 * 这三点都是行为约定，只有把真源码（含 Vue 的 watch 调度）跑起来才能验证。
 *
 * 浏览器端的最后一段（真实刷新后弹"恢复草稿"）由浏览器实测覆盖：
 * 本脚本负责存储契约与节流/基线语义，两者互补。
 *
 * 运行：node verify-v03-draft.mjs
 * 退出码：0 = 全通过，1 = 有失败用例
 */
import { reactive, nextTick } from 'vue'
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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/* ============ localStorage 桩（可注入读写故障，模拟隐私模式 / 配额写满） ============ */
const kv = new Map()
let blockWrite = false
let blockRead = false
globalThis.localStorage = {
  getItem: (k) => {
    if (blockRead) throw new Error('SecurityError')
    return kv.has(k) ? kv.get(k) : null
  },
  setItem: (k, v) => {
    if (blockWrite) throw new Error('QuotaExceededError')
    kv.set(k, String(v))
  },
  removeItem: (k) => kv.delete(k),
  clear: () => kv.clear(),
}

/* ============ sessionStorage 桩（恢复询问的会话态；可注入故障模拟隐私模式） ============ */
const skv = new Map()
let blockSession = false
globalThis.sessionStorage = {
  getItem: (k) => {
    if (blockSession) throw new Error('SecurityError')
    return skv.has(k) ? skv.get(k) : null
  },
  setItem: (k, v) => {
    if (blockSession) throw new Error('SecurityError')
    skv.set(k, String(v))
  },
  removeItem: (k) => skv.delete(k),
  clear: () => skv.clear(),
}

/* ============ 加载真实源码 ============ */
const vite = await createViteServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
})
const mod = await vite.ssrLoadModule('/src/composables/useDraft.ts')
const {
  readDraft, writeDraft, clearDraft, draftKey, DRAFT_VERSION, isEmptyDraft, useDraft,
  pickNewerDraft, REMOTE_TOLERANCE_MS,
  draftDecisionKey, rememberDraftDecision, recallDraftDecision,
} = mod

const fields = (over = {}) => ({ title: '', content: '', boardId: null, type: 0, tags: [], ...over })

/* ================= A. 存储契约 ================= */
console.log('\n[A] 存储契约（读写 / 自愈 / 隔离 / 故障降级）')

const payload = fields({ title: '虚拟线程实测', content: '正文内容 ≥10 字', boardId: 2, type: 1, tags: ['Java', '并发'] })
check('非空草稿写入成功', writeDraft('a', payload) === true)
const back = readDraft('a')
check('读回的字段与写入一致',
  back && back.title === '虚拟线程实测' && back.content === '正文内容 ≥10 字' &&
  back.boardId === 2 && back.type === 1 && back.tags.join(',') === 'Java,并发',
  JSON.stringify(back))
check('落盘数据带版本号', back && back.version === DRAFT_VERSION, back && back.version)

check('空草稿不落盘（标题正文皆空）', writeDraft('empty', fields({ tags: ['只有标签'] })) === false)
check('空草稿判定：仅有标签也算空', isEmptyDraft(fields({ tags: ['x'] })) === true)
check('空草稿没有留下键', kv.get(draftKey('empty')) === undefined)

clearDraft('a')
check('clear 后读回 null', readDraft('a') === null)
check('clear 后键已删除', !kv.has(draftKey('a')))

// 版本不匹配：旧结构不能按新结构解析，必须丢弃并自愈
kv.set(draftKey('badver'), JSON.stringify({ version: DRAFT_VERSION - 1, title: '旧', content: '旧结构' }))
check('版本不匹配 → 读回 null', readDraft('badver') === null)
check('版本不匹配 → 坏键被清掉（自愈）', !kv.has(draftKey('badver')))

// 结构损坏：非法 JSON / content 不是字符串
kv.set(draftKey('broken'), '{不是合法 JSON')
check('JSON 损坏 → 读回 null 且不抛异常', readDraft('broken') === null)
check('JSON 损坏 → 坏键被清掉', !kv.has(draftKey('broken')))
kv.set(draftKey('broken2'), JSON.stringify({ version: DRAFT_VERSION, content: 42 }))
check('content 类型不对 → 读回 null 且不抛异常', readDraft('broken2') === null)
check('content 类型不对 → 坏键被清掉', !kv.has(draftKey('broken2')))

// scope 隔离：不同帖子 / 新建稿互不污染
writeDraft('new', fields({ content: '新建稿内容' }))
writeDraft('edit:7', fields({ content: '7 号帖的编辑稿' }))
check('scope 隔离：new 与 edit:7 各自独立',
  readDraft('new').content === '新建稿内容' && readDraft('edit:7').content === '7 号帖的编辑稿')

// 故障降级：存储不可用时功能退化为"没有草稿"，但不能让编辑页崩
blockWrite = true
check('配额写满 → 写入返回 false 且不抛异常', writeDraft('quota', fields({ content: '写不进去' })) === false)
blockWrite = false
blockRead = true
check('读取被拒（隐私模式）→ 返回 null 且不抛异常', readDraft('new') === null)
blockRead = false

/* ================= B. useDraft 组合式（节流 + 基线） ================= */
console.log('\n[B] useDraft：节流、基线比较与清理时机')

const form = reactive(fields())
const ctl = useDraft({
  scope: () => 'editor',
  snapshot: () => ({ ...form, tags: [...form.tags] }),
  throttleMs: 60, // 验收用小窗口，真实值为 5s
})

ctl.flush()
check('start 前不写盘（尚无基线，谈不上"改动"）', !kv.has(draftKey('editor')))

ctl.start()
await sleep(120)
check('start 后无改动 → 不写盘（初始回填不会被当成用户输入）', !kv.has(draftKey('editor')))

form.title = '用户开始输入了'
await nextTick()
check('改动后立刻不等于落盘（节流窗口内）', !kv.has(draftKey('editor')))
await sleep(160)
const saved = readDraft('editor')
check('节流窗口过去后自动落盘', saved && saved.title === '用户开始输入了', JSON.stringify(saved))

form.content = '又写了一行'
await nextTick()
ctl.flush()
check('flush 立即落盘（刷新/卸载路径不等节流）',
  readDraft('editor').content === '又写了一行', JSON.stringify(readDraft('editor')))

check('isDirty 反映"与基线不同"', ctl.isDirty() === true)

// 改回基线（等价于用户清空并还原）→ 草稿失去意义，应被清掉
form.title = ''
form.content = ''
await nextTick()
await sleep(20)
check('内容改回基线 → 草稿被清理', readDraft('editor') === null)
check('改回基线后 isDirty 为 false', ctl.isDirty() === false)

/* ---- 账号隔离（安全审计 run-1 线索 2）：本地键必须带 owner 维度 ---- */
const formA = reactive(fields())
const ctlA = useDraft({
  scope: () => 'shared',
  owner: () => 7,
  snapshot: () => ({ ...formA, tags: [...formA.tags] }),
  throttleMs: 20,
})
ctlA.start()
formA.content = 'A 账号的未发布草稿'
await nextTick()
ctlA.flush()
check('带 owner 的草稿写入 owner 命名空间键', kv.has(draftKey('7:shared')), draftKey('7:shared'))

const formB = reactive(fields())
const ctlB = useDraft({
  scope: () => 'shared',
  owner: () => 8,
  snapshot: () => ({ ...formB, tags: [...formB.tags] }),
  throttleMs: 20,
})
ctlB.start()
check('换账号（不同 owner）读不到上一账号的草稿', ctlB.peek() === null, JSON.stringify(ctlB.peek()))
ctlA.stop()
ctlB.stop()
clearDraft('7:shared')
check('owner 命名空间键可正常清理', !kv.has(draftKey('7:shared')))

// 发布成功路径：clear() 之后即使再 flush 也不能把内容写回去
form.title = '准备发布'
form.content = '发布前的正文内容'
await nextTick()
ctl.flush()
check('发布前草稿在（基线已偏离）', readDraft('editor') !== null)
ctl.clear()
check('clear() 后草稿被删除（发布成功）', readDraft('editor') === null)
ctl.flush()
check('clear() 之后再 flush 也不会写回（跳转/卸载不会复活草稿）', readDraft('editor') === null)

// peek：只读不决策，空草稿不打扰用户
form.title = ''
form.content = ''
await nextTick()
check('空草稿 peek 返回 null（不进恢复流程）', ctl.peek() === null)
writeDraft('editor', fields({ title: '上次没写完', content: '恢复我' }))
const peeked = ctl.peek()
check('peek 读回已存草稿且不改动它',
  peeked && peeked.title === '上次没写完' && readDraft('editor') !== null)

ctl.stop()

/* ================= C. 本地 / 云端并存：恢复哪一份（M4-15） ================= */
console.log('\n[C] pickNewerDraft：两侧都有草稿时"哪份算数"')

const T = 1_790_000_000_000
const localAt = (ms, over = {}) => ({ ...fields(over), version: DRAFT_VERSION, updatedAt: ms })
const cloudAt = (ms, over = {}) => ({ ...fields(over), version: DRAFT_VERSION, updatedAt: ms })

check('只有本地 → 用本地', pickNewerDraft(localAt(T, { content: '本地' }), null)?.content === '本地')
check('只有云端 → 用云端', pickNewerDraft(null, cloudAt(T, { content: '云端' }))?.content === '云端')
check('两侧都没有 → null', pickNewerDraft(null, null) === null)
// 同一次保存写两侧：云端时间必然略晚一点点，不该被当成"另一台设备写过"
check('云端仅晚 2 秒（容差内）→ 仍用本地',
  pickNewerDraft(localAt(T, { content: '本地' }), cloudAt(T + 2000, { content: '云端' }))?.content === '本地')
check('容差边界恰好 5 秒 → 用本地（必须"明显更新"才算云端赢）',
  pickNewerDraft(localAt(T, { content: '本地' }), cloudAt(T + REMOTE_TOLERANCE_MS, { content: '云端' }))?.content === '本地')
check('云端晚 6 秒（超容差）→ 用云端（另一台设备写过）',
  pickNewerDraft(localAt(T, { content: '本地' }), cloudAt(T + 6000, { content: '云端' }))?.content === '云端')
check('云端比本地旧 → 用本地（本机离线写过的场景）',
  pickNewerDraft(localAt(T + 9000, { content: '本地' }), cloudAt(T, { content: '云端' }))?.content === '本地')

/* ================= D. 云端链路：读取择优 / 节流推送 / 失败降级（M4-15） ================= */
console.log('\n[D] useDraft 云端链路（假 remote 注入，不依赖后端）')

const form2 = reactive(fields())
const calls = { load: [], save: [], clear: [] }
let remoteDraft = null
let failSaves = 0

const remoteStub = {
  load: async (scope) => {
    calls.load.push(scope)
    return remoteDraft
  },
  save: async (scope, payload) => {
    calls.save.push({ scope, ...payload })
    if (failSaves > 0) {
      failSaves--
      throw new Error('模拟推送失败')
    }
  },
  clear: async (scope) => {
    calls.clear.push(scope)
  },
}

clearDraft('remote')
const ctl2 = useDraft({
  scope: () => 'remote',
  snapshot: () => ({ ...form2, tags: [...form2.tags] }),
  throttleMs: 40,
  remoteThrottleMs: 60,
  remote: remoteStub,
})

check('peekNewest：两侧都没有 → null', (await ctl2.peekNewest()) === null)
check('peekNewest 会拿同一 scope 去问云端', calls.load.includes('remote'), calls.load)

writeDraft('remote', fields({ title: '本地稿', content: '本地写的内容' }), T)
remoteDraft = cloudAt(T + 60000, { title: '云端稿', content: '云端写的内容' })
const picked = await ctl2.peekNewest()
check('peekNewest：云端明显更新 → 选云端（换设备续写的核心路径）',
  picked && picked.content === '云端写的内容', picked && picked.content)

// 云端读取失败必须降级为"只有本地"，且不抛异常（它跑在编辑器初始化的主链路上）
const ctlBroken = useDraft({
  scope: () => 'remote',
  snapshot: () => ({ ...form2, tags: [...form2.tags] }),
  remote: { ...remoteStub, load: async () => { throw new Error('模拟云端不可用') } },
})
const fallback = await ctlBroken.peekNewest()
check('peekNewest：云端读取失败 → 降级为本地那份且不抛',
  fallback && fallback.content === '本地写的内容', fallback && fallback.content)
ctlBroken.stop()

// 与真实编辑器一致：恢复决策做完后才 start()（start 前没有基线，谈不上"改动"）
ctl2.start()

// 推送：flush 立即推一次，负载要带业务字段 + 版本 + 时间戳
form2.title = '推送测试'
form2.content = '推送到云端的正文'
await nextTick()
ctl2.flush()
await sleep(20)
check('flush 立即向云端推一份（scope 正确）',
  calls.save.length === 1 && calls.save[0].scope === 'remote', JSON.stringify(calls.save[0] || null))
check('推送负载含业务字段、版本号与时间戳',
  calls.save[0]?.title === '推送测试' && calls.save[0]?.version === DRAFT_VERSION
  && typeof calls.save[0]?.updatedAt === 'number',
  JSON.stringify(calls.save[0] || null))

ctl2.flush()
await sleep(20)
check('内容没变 → 不重复推送（指纹去重）', calls.save.length === 1, calls.save.length)

form2.content = '又写了一句'
await nextTick()
await sleep(200)
check('改动后到节流点自动推送（无需手动触发）',
  calls.save.length === 2 && calls.save[1].content === '又写了一句', JSON.stringify(calls.save[1] || null))

// 推送失败：不打扰用户（不抛），本地照常保住内容；下一次机会要能重试
failSaves = 1
form2.content = '这次推送会失败'
await nextTick()
ctl2.flush()
await sleep(20)
check('推送失败不抛异常，本地草稿照常落盘',
  readDraft('remote')?.content === '这次推送会失败', readDraft('remote')?.content)
ctl2.flush()
await sleep(20)
check('失败后下次触发会重试（指纹已复位）',
  calls.save.length === 4 && calls.save[3].content === '这次推送会失败', calls.save.length)

ctl2.clear()
await sleep(20)
check('clear()：本地与云端一起清（发布成功 / 用户放弃）',
  readDraft('remote') === null && calls.clear.includes('remote'),
  JSON.stringify({ local: readDraft('remote'), clears: calls.clear }))

// "回到基线"路径同样要清云端 —— 否则下次进入会弹一个用户已经清空的旧草稿
form2.title = '临时标题'
form2.content = '临时正文'
await nextTick()
ctl2.flush()
await sleep(20)
const clearsBefore = calls.clear.length
form2.title = '推送测试'
form2.content = '这次推送会失败'
await nextTick()
await sleep(40)
check('内容改回基线 → 云端那份也被删（避免旧草稿复活）',
  calls.clear.length === clearsBefore + 1 && readDraft('remote') === null,
  JSON.stringify({ clears: calls.clear, local: readDraft('remote') }))

ctl2.stop()

// remote 的可选性：不传 remote 时行为与 M2-10 完全一致、零请求。
// 注意这不是"游客在编辑器里"的路径 —— `/publish` 本身要求登录（游客被守卫拦走），
// 它是 composable 层面的正确性质，也是"远端不可用时只有本地这一侧"的兜底形态。
const ctlLocal = useDraft({
  scope: () => 'localonly',
  snapshot: () => ({ ...form2, tags: [...form2.tags] }),
})
writeDraft('localonly', fields({ content: '只在本地' }), T)
const only = await ctlLocal.peekNewest()
check('未接远端：peekNewest 返回本地那份（remote 可不传，行为与 M2-10 一致）',
  only && only.content === '只在本地')
ctlLocal.stop()
clearDraft('remote')
clearDraft('localonly')

/* ================= E. 恢复询问的会话态（备注 20 ⑤：同一版草稿本会话只问一次） ================= */
console.log('\n[E] 恢复询问的会话态（kept / restored 两种表态）')

const decDraft = {
  version: DRAFT_VERSION, title: '会话态', content: '内容至少十个字',
  boardId: null, type: 0, tags: [], updatedAt: 1000,
}
const decDraftV2 = { ...decDraft, updatedAt: 2000 }

check('决策键含草稿版本指纹（updatedAt 不同 → 键不同）',
  draftDecisionKey('new', decDraft) !== draftDecisionKey('new', decDraftV2))
check('初始 recall 为空（没表过态 → 照常询问）', recallDraftDecision('new', decDraft) === null)
rememberDraftDecision('new', decDraft, 'kept')
check('kept 被记住（关掉对话框后本会话不再重复询问）',
  recallDraftDecision('new', decDraft) === 'kept')
check('同一 scope 的新版草稿不受旧决定影响（新版重新询问）',
  recallDraftDecision('new', decDraftV2) === null)
check('scope 隔离：另一个 scope 不受影响', recallDraftDecision('edit:7', decDraft) === null)
rememberDraftDecision('new', decDraft, 'restored')
check('同会话改主意：restored 覆盖 kept',
  recallDraftDecision('new', decDraft) === 'restored')
rememberDraftDecision('edit:7', decDraft, 'kept')
check('不同 scope 的决策互相独立',
  recallDraftDecision('edit:7', decDraft) === 'kept' && recallDraftDecision('new', decDraft) === 'restored')

skv.set(draftDecisionKey('new', decDraft), 'maybe')
check('非法决策值按"没表过态"处理（不误吞询问）',
  recallDraftDecision('new', decDraft) === null)
skv.delete(draftDecisionKey('new', decDraft))

blockSession = true
let writeThrew = false
try { rememberDraftDecision('new', decDraft, 'kept') } catch { writeThrew = true }
check('sessionStorage 抛异常时写入不冒泡（静默降级）', writeThrew === false)
check('sessionStorage 不可用时 recall 为 null（降级为照常询问）',
  recallDraftDecision('new', decDraft) === null)
blockSession = false

/* ============ 收尾 ============ */
await vite.close()

console.log('\n' + '='.repeat(58))
console.log(`草稿自动保存验证：${pass}/${pass + fail} 通过`)
if (failures.length) console.log('失败：' + failures.join(' | '))
console.log('='.repeat(58))
process.exit(fail ? 1 : 0)