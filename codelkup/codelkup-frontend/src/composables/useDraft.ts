/**
 * 草稿自动保存（M2-10 / ADR-6）。
 *
 * <h2>为什么是 localStorage 而不是服务端草稿表</h2>
 * ADR-6 已经定过口径：本需求是"防误关"，不是"多端同步"。加一张草稿表要面临
 * 多端冲突、"谁的草稿算数"、过期清理三件新问题，而收益只覆盖"同一台设备"。
 * 单人演示站场景下 localStorage 足够，且零后端改动、零新接口。
 *
 * <h2>几个刻意的设计</h2>
 * <ol>
 *   <li><b>键里带版本号</b>（{@code codelkup_draft_v1_<scope>}）：结构一旦变化，
 *       旧数据不会被当成新结构解析出"半截表单"。版本不匹配一律丢弃 ——
 *       宁可少恢复一次，也不要恢复出一个字段错位的表单。</li>
 *   <li><b>scope 隔离</b>：新建稿是 {@code new}，编辑稿是 {@code edit:<postId>}。
 *       否则"编辑 A 帖时写的草稿"会在"编辑 B 帖"时被提示恢复。</li>
 *   <li><b>与基线比较后才写盘</b>：进编辑页时组件会用服务端内容回填表单，
 *       若不做基线比较，这次回填本身就会被当成"用户输入"，5 秒后覆盖掉
 *       用户上一次真正的草稿。</li>
 *   <li><b>所有读写都包 try/catch</b>：隐私模式、配额写满时 localStorage 会抛异常。
 *       草稿功能失败不应该让编辑页不可用 —— 降级为"没有草稿"即可。</li>
 *   <li><b>节流 5 秒 + 卸载/刷新时立即落盘</b>：连续输入只写一次；
 *       而"刷新页面"这种来不及等节流的情况，由 beforeunload 与组件卸载兜底，
 *       否则用户按下刷新那一刻的最后几秒输入会丢。</li>
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
}

/**
 * 草稿控制器。
 *
 * 生命周期由调用方显式驱动，而不是一进来就偷偷开始保存：
 * <pre>
 *   const draft = useDraft({ scope, snapshot })
 *   onMounted(async () => {
 *     await 加载数据()
 *     const pending = draft.peek()      // 有草稿 → 弹恢复提示
 *     ...
 *     draft.start()                     // 初始回填完成后再开始自动保存
 *   })
 * </pre>
 */
export function useDraft(options: UseDraftOptions) {
  const throttleMs = options.throttleMs ?? 5000

  /** 基线：初始加载完成时的表单内容。"与基线相同"意味着没什么可保存的 */
  let baseline: DraftFields | null = null
  let timer: ReturnType<typeof setTimeout> | null = null
  let stopWatch: (() => void) | null = null
  let onUnload: (() => void) | null = null

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

  function persist() {
    if (!isDirty()) return
    writeDraft(options.scope(), options.snapshot())
  }

  /** 立即落盘（忽略节流），用于刷新/卸载这类"等不到下一次定时"的时机 */
  function flush() {
    cancelTimer()
    persist()
  }

  /** 开始自动保存；调用前应先完成初始回填与草稿恢复决策 */
  function start() {
    baseline = clone(options.snapshot())

    stopWatch = watch(
      options.snapshot,
      () => {
        if (!isDirty()) {
          // 用户把内容改回基线（例如清空表单）→ 草稿已无意义，顺手删掉
          cancelTimer()
          clearDraft(options.scope())
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

  /** 丢弃草稿并重置基线（发布成功、用户明确"放弃"时调用） */
  function clear() {
    cancelTimer()
    clearDraft(options.scope())
    baseline = clone(options.snapshot())
  }

  /** 停止自动保存（组件卸载时自动调用） */
  function stop() {
    stopWatch?.()
    stopWatch = null
    cancelTimer()
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

  return { peek, isDirty, start, flush, clear, stop }
}