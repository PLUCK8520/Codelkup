/**
 * 草稿自动保存（M2-10 / ADR-6 → M4-15 升级为"本地 + 云端"双层）。
 *
 * <h2>两层存储的分工（M4-15 结论）</h2>
 * <ol>
 *   <li><b>localStorage = 本机写前日志</b>：每 5 秒落盘、离线可用、无需登录，
 *       是"防误关"的最后一道保险。</li>
 *   <li><b>服务端草稿 = 跨设备副本</b>：补上本地做不到的两件事 ——
 *       换设备接着写、清掉浏览器数据后内容还在（见 useDraftOptions.remote）。</li>
 * </ol>
 * 二者是<b>叠层</b>不是替代：远端不可用（未登录 / 请求失败 / 超限流）时，
 * 本地链路照常工作，编辑器不因服务端故障而不可用。
 *
 * <h2>几个刻意的设计</h2>
 * <ol>
 *   <li><b>键里带版本号</b>（{@code codelkup_draft_v1_<scope>}）：结构一旦变化，
 *       旧数据不会被当成新结构解析出"半截表单"。版本不匹配一律丢弃 ——
 *       宁可少恢复一次，也不要恢复出一个字段错位的表单。</li>
 *   <li><b>scope 隔离</b>：新建稿是 {@code new}，编辑稿是 {@code edit:<postId>}。
 *       这个字符串同时是本地键与远端 scope，两侧天然对齐，不需要任何映射表。</li>
 *   <li><b>与基线比较后才写盘</b>：进编辑页时组件会用服务端内容回填表单，
 *       若不做基线比较，这次回填本身就会被当成"用户输入"，5 秒后覆盖掉
 *       用户上一次真正的草稿。</li>
 *   <li><b>所有本地读写都包 try/catch</b>：隐私模式、配额写满时 localStorage 会抛异常。
 *       草稿功能失败不应该让编辑页不可用 —— 降级为"没有草稿"即可。</li>
 *   <li><b>节流 5 秒 + 卸载/刷新时立即落盘</b>：连续输入只写一次；
 *       而"刷新页面"这种来不及等节流的情况，由 beforeunload 与组件卸载兜底，
 *       否则用户按下刷新那一刻的最后几秒输入会丢。</li>
 *   <li><b>远端节流更宽（15 秒）且失败静默</b>：远端是副本、不是写前日志，
 *       没必要按本地的节拍打接口；失败也不打扰用户（本地已经把内容保住了，
 *       下一次输入或离开页面时自然重试）。</li>
 * </ol>
 *
 * @author 刘
 */
import { getCurrentInstance, onBeforeUnmount, watch } from 'vue'

/** 表单里需要落盘的部分（与 PostEditorView 的 form 字段同名） */
export interface DraftFields {
  title: string
  content: string
  boardId: number | null
  type: number
  tags: string[]
}

/** 落盘负载：业务字段 + 元信息（版本、更新时间） */
export interface DraftPayload extends DraftFields {
  version: number
  updatedAt: number
}

export const DRAFT_VERSION = 1

const KEY_PREFIX = 'codelkup_draft'

/** 草稿键。带版本号，版本升级后旧键自然"认不出来"，不会被误读 */
export function draftKey(scope: string, version: number = DRAFT_VERSION): string {
  return `${KEY_PREFIX}_v${version}_${scope}`
}

/** 标题与正文都为空 = 没有可恢复的内容（只有标签/板块不算草稿） */
export function isEmptyDraft(fields: Pick<DraftFields, 'title' | 'content'>): boolean {
  return !fields.title.trim() && !fields.content.trim()
}

export function readDraft(scope: string): DraftPayload | null {
  let raw: string | null = null
  try {
    raw = localStorage.getItem(draftKey(scope))
  } catch {
    return null // 隐私模式下 getItem 也可能抛
  }
  if (!raw) return null

  try {
    const parsed = JSON.parse(raw) as Partial<DraftPayload>
    if (parsed?.version !== DRAFT_VERSION || typeof parsed.content !== 'string') {
      // 版本不符 / 结构不对 → 丢掉，不尝试"尽力解析"
      clearDraft(scope)
      return null
    }
    return {
      version: DRAFT_VERSION,
      title: typeof parsed.title === 'string' ? parsed.title : '',
      content: parsed.content,
      boardId: typeof parsed.boardId === 'number' ? parsed.boardId : null,
      type: typeof parsed.type === 'number' ? parsed.type : 0,
      tags: Array.isArray(parsed.tags) ? parsed.tags.filter((t) => typeof t === 'string') : [],
      updatedAt: typeof parsed.updatedAt === 'number' ? parsed.updatedAt : 0,
    }
  } catch {
    clearDraft(scope)
    return null
  }
}

export function writeDraft(scope: string, fields: DraftFields, now: number = Date.now()): boolean {
  if (isEmptyDraft(fields)) {
    clearDraft(scope)
    return false
  }
  const payload: DraftPayload = { ...fields, version: DRAFT_VERSION, updatedAt: now }
  try {
    localStorage.setItem(draftKey(scope), JSON.stringify(payload))
    return true
  } catch {
    return false // 配额满：放弃保存，不打断编辑
  }
}

export function clearDraft(scope: string): void {
  try {
    localStorage.removeItem(draftKey(scope))
  } catch {
    /* 删不掉也不影响使用 */
  }
}

export interface UseDraftOptions {
  /** 草稿归属（new / edit:<id>）；用函数是为了路由参数变化时能取到最新值 */
  scope: () => string
  /** 取当前表单快照 */
  snapshot: () => DraftFields
  /** 节流窗口，默认 5 秒 */
  throttleMs?: number
  /**
   * 远端草稿通道（服务端草稿箱）。
   *
   * 不传 = 纯本地模式（未登录时就是这个状态，零请求）。
   * 传了但请求失败不抛给调用方：save/clear 由本函数吞掉异常（远端是副本，
   * 失败不该影响本地链路），load 的失败由 peekNewest 吞掉并降级为"只有本地"。
   */
  remote?: DraftRemote
  /**
   * 远端节流窗口，默认 15 秒。
   *
   * 为什么不跟本地一样是 5 秒：本地写的是内存级操作（写前日志），愈频繁愈安全；
   * 远端是 HTTP 请求，按 15 秒推送已足够满足"跨设备最多落后十几秒"，
   * 还把接口调用量压到 1/3（对应后端 240 次/小时的限流额度，留有余量）。
   */
  remoteThrottleMs?: number
}

/** 远端草稿通道 —— 由调用方用 draftApi 适配，composable 本身不依赖 api 层 */
export interface DraftRemote {
  /** 读回该 scope 的远端草稿；不存在返回 null */
  load(scope: string): Promise<DraftPayload | null>
  /** 保存（upsert） */
  save(scope: string, payload: DraftPayload): Promise<void>
  /** 删除 */
  clear(scope: string): Promise<void>
}

/** 远端时间容差：两次保存的服务器时间必然略晚于本地时间，这点偏差不算"远端更新" */
export const REMOTE_TOLERANCE_MS = 5000

/**
 * 决定"恢复哪一份"：两侧都有时取更新的一份。
 *
 * 规则本身导出（而不是埋进 peekNewest）是为了让验收脚本能直接断言这条规则 ——
 * "哪个更算数"是本地与云端并存时唯一真正需要判断的事，值得单独钉死：
 * <ul>
 *   <li>只有一份 → 用它</li>
 *   <li>都有 → 远端比本地新出 <b>超过容差</b> 才用远端；否则用本地
 *       （同一次保存写两侧时，远端时间总晚一点点，不该因此把本地判成过期）</li>
 * </ul>
 */
export function pickNewerDraft(
  local: DraftPayload | null,
  cloud: DraftPayload | null,
): DraftPayload | null {
  if (!local) return cloud
  if (!cloud) return local
  return cloud.updatedAt - local.updatedAt > REMOTE_TOLERANCE_MS ? cloud : local
}

/* ==================== 恢复询问的"会话态"（备注 20 ⑤ 修补） ==================== */

/** 同一标签页会话里，用户对"某一版草稿"的表态：restored=选过恢复；kept=关掉对话框（暂不恢复） */
export type DraftRestoreDecision = 'restored' | 'kept'

const DECISION_KEY_PREFIX = 'codelkup_draft_decision'

/**
 * 决策键 = scope + 该版草稿的 {@link DraftPayload.updatedAt} 指纹。
 *
 * 为什么必须带 updatedAt：草稿被继续编辑后会写入新的时间 —— 那是一版**新**草稿，
 * 理应重新询问；只有"同一版"才复用旧决定。否则用户在第一版上关掉过一次对话框，
 * 之后每次真实的更新都再也不问了，比重复询问更糟。
 */
export function draftDecisionKey(scope: string, draft: DraftPayload): string {
  return `${DECISION_KEY_PREFIX}_v${DRAFT_VERSION}_${scope}_${draft.updatedAt}`
}

/**
 * 记录用户对某版草稿的恢复表态（会话级）。
 *
 * ⚠️ 用 sessionStorage 而不是 localStorage：这是**会话态** —— 关掉标签页就该忘掉，
 * 下次打开重新询问是正确行为（用户可能早已不记得那份草稿的存在）；而 localStorage
 * 会让很久以前的决定继续生效，那比重复询问更糟。
 * 存储不可用（隐私模式等）时静默降级回"每次询问"的旧行为，绝不让编辑页出错。
 */
export function rememberDraftDecision(
  scope: string,
  draft: DraftPayload,
  decision: DraftRestoreDecision,
): void {
  try {
    globalThis.sessionStorage?.setItem(draftDecisionKey(scope, draft), decision)
  } catch {
    /* 存不进去就退回旧行为 */
  }
}

/** 读取某版草稿的会话表态；没有 / 存储不可用 / 值非法一律返回 null（照常询问） */
export function recallDraftDecision(scope: string, draft: DraftPayload): DraftRestoreDecision | null {
  try {
    const raw = globalThis.sessionStorage?.getItem(draftDecisionKey(scope, draft))
    return raw === 'restored' || raw === 'kept' ? raw : null
  } catch {
    return null
  }
}

/**
 * 草稿控制器。
 *
 * 生命周期由调用方显式驱动，而不是一进来就偷偷开始保存：
 * <pre>
 *   const draft = useDraft({ scope, snapshot, remote })
 *   onMounted(async () => {
 *     await 加载数据()
 *     const pending = await draft.peekNewest()  // 本地与云端取新者 → 弹恢复提示
 *     ...
 *     draft.start()                             // 初始回填完成后再开始自动保存
 *   })
 * </pre>
 *
 * 纯本地场景（未登录）用 {@code peek()} 也一样；{@code peekNewest()} 在没有
 * {@code remote} 时就是同步返回本地那份。
 */
export function useDraft(options: UseDraftOptions) {
  const throttleMs = options.throttleMs ?? 5000
  const remoteThrottleMs = options.remoteThrottleMs ?? 15000

  /** 基线：初始加载完成时的表单内容。"与基线相同"意味着没什么可保存的 */
  let baseline: DraftFields | null = null
  let timer: ReturnType<typeof setTimeout> | null = null
  let stopWatch: (() => void) | null = null
  let onUnload: (() => void) | null = null

  /* ---- 远端链路状态 ---- */
  let remoteTimer: ReturnType<typeof setTimeout> | null = null
  /**
   * 上一次已提交/正在提交的远端负载指纹（不含时间戳）。
   * 内容没变时不重复 PUT —— 用户长时间停在页面上时，节流窗口每到点就发一次
   * 完全相同的内容是纯粹的浪费。
   */
  let lastRemoteFingerprint: string | null = null

  function clone(f: DraftFields): DraftFields {
    return { ...f, tags: [...f.tags] }
  }

  function sameAs(fields: DraftFields, other: DraftFields): boolean {
    return (
      fields.title === other.title &&
      fields.content === other.content &&
      fields.boardId === other.boardId &&
      fields.type === other.type &&
      fields.tags.join('\u0000') === other.tags.join('\u0000')
    )
  }

  /** 读一次已存草稿（不改变内部状态）—— 由调用方决定恢复还是丢弃 */
  function peek(): DraftPayload | null {
    const payload = readDraft(options.scope())
    return payload && !isEmptyDraft(payload) ? payload : null
  }

  /**
   * 取"该恢复的那一份"：本地与远端各取一次，按 {@link pickNewerDraft} 选新者。
   *
   * 远端读取失败（未登录 / 网络异常 / 服务端故障）一律降级为"只有本地"——
   * 这条路径必须永远不会抛：它跑在编辑器初始化的主链路上，
   * 一旦抛出就会把整个编辑页带下去。
   */
  async function peekNewest(): Promise<DraftPayload | null> {
    const local = peek()
    const remote = options.remote
    if (!remote) return local

    let cloud: DraftPayload | null = null
    try {
      const loaded = await remote.load(options.scope())
      // 远端理论上不会存空草稿（客户端不会推空的）；但真出现脏数据时
      // 不能拿它去打扰用户，"空草稿不算草稿"的判定两边保持一致
      cloud = loaded && !isEmptyDraft(loaded) ? loaded : null
    } catch {
      cloud = null
    }
    return pickNewerDraft(local, cloud)
  }

  /** 当前内容是否偏离基线（="有未保存的改动"） */
  function isDirty(): boolean {
    return baseline !== null && !sameAs(options.snapshot(), baseline)
  }

  function cancelTimer() {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  /* ==================== 远端链路 ==================== */

  function cancelRemoteTimer() {
    if (remoteTimer !== null) {
      clearTimeout(remoteTimer)
      remoteTimer = null
    }
  }

  /** 负载指纹：只含业务字段，不含时间戳（时间戳每次都不同，起不到"内容没变"的判据作用） */
  function fingerprint(fields: DraftFields): string {
    return JSON.stringify([fields.title, fields.content, fields.boardId, fields.type, fields.tags])
  }

  /**
   * 推送一次远端保存。
   *
   * 不 await、不抛错：远端是副本，它失败时本地草稿已经落盘（persist 先写本地），
   * 编辑器不需要知道。失败后会把指纹清掉，让下一次输入或离开页面时自然重试。
   */
  function pushRemote() {
    const remote = options.remote
    if (!remote || !isDirty()) return

    const fields = options.snapshot()
    const fp = fingerprint(fields)
    if (fp === lastRemoteFingerprint) return
    lastRemoteFingerprint = fp

    remote
      .save(options.scope(), { ...fields, version: DRAFT_VERSION, updatedAt: Date.now() })
      .catch(() => {
        // 失败 → 允许重试（否则一次抖动会让这份草稿在本页会话里再也不推送）
        lastRemoteFingerprint = null
      })
  }

  /** 节流排程：窗口内已有待推送任务就不排新的 */
  function scheduleRemote() {
    if (!options.remote || remoteTimer !== null) return
    remoteTimer = setTimeout(() => {
      remoteTimer = null
      pushRemote()
    }, remoteThrottleMs)
  }

  function persist() {
    if (!isDirty()) return
    writeDraft(options.scope(), options.snapshot())
    scheduleRemote()
  }

  /** 立即落盘（忽略节流），用于刷新/卸载这类"等不到下一次定时"的时机 */
  function flush() {
    cancelTimer()
    persist()
    // 远端也立即推一次：SPA 内的卸载不会中断在途请求（fetch 照常完成），
    // 而整页关闭时即使请求被取消，本地草稿仍在 —— 下次进入会把它推上去
    cancelRemoteTimer()
    pushRemote()
  }

  /** 删掉远端那一份（fire-and-forget；失败不打扰用户，留待下次进入时按"回到基线"再清） */
  function clearRemote() {
    if (!options.remote) return
    lastRemoteFingerprint = null
    options.remote.clear(options.scope()).catch(() => {})
  }

  /** 开始自动保存；调用前应先完成初始回填与草稿恢复决策 */
  function start() {
    baseline = clone(options.snapshot())

    stopWatch = watch(
      options.snapshot,
      () => {
        if (!isDirty()) {
          // 用户把内容改回基线（例如清空表单）→ 草稿已无意义，顺手删掉。
          // ⚠️ 远端也要一起删：只删本地的话，下次进入编辑器远端那份"旧草稿"
          // 会被当成有效内容再弹一次恢复提示（明明用户已经清空了）。
          cancelTimer()
          clearDraft(options.scope())
          cancelRemoteTimer()
          clearRemote()
          return
        }
        // 节流：窗口内已有待写入任务就不再排新任务
        if (timer !== null) return
        timer = setTimeout(() => {
          timer = null
          persist()
        }, throttleMs)
      },
      { deep: true },
    )

    // 刷新/关闭标签页：节流窗口里的最后几秒输入不能丢
    onUnload = () => flush()
    globalThis.addEventListener?.('beforeunload', onUnload)
  }

  /** 丢弃草稿并重置基线（发布成功、用户明确"放弃"时调用）；本地与远端一起删 */
  function clear() {
    cancelTimer()
    clearDraft(options.scope())
    cancelRemoteTimer()
    clearRemote()
    baseline = clone(options.snapshot())
  }

  /** 停止自动保存（组件卸载时自动调用） */
  function stop() {
    stopWatch?.()
    stopWatch = null
    cancelTimer()
    cancelRemoteTimer()
    if (onUnload) {
      globalThis.removeEventListener?.('beforeunload', onUnload)
      onUnload = null
    }
  }

  // 组件卸载：先把节流窗口里的改动落盘，再摘掉监听。
  // getCurrentInstance 判空是为了让纯逻辑验收脚本（无组件实例）也能调用本函数。
  if (getCurrentInstance()) {
    onBeforeUnmount(() => {
      flush()
      stop()
    })
  }

  return { peek, peekNewest, isDirty, start, flush, clear, stop }
}