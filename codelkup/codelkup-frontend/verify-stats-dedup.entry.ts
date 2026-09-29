/**
 * verify-stats-dedup.mjs 的加载入口（测试夹具，不参与应用构建）。
 *
 * 两个必须由 Vite 统一加载的理由：
 *
 * 1. **axios 实例的 defaults 是"创建时快照"**。
 *    `src/utils/request.ts` 在模块加载时就 `axios.create(...)`，
 *    所以 adapter 必须在**加载 request.ts 之前**装好 —— 之后再改全局 defaults 不会被吸收。
 *    这里用动态 import 控制顺序（ESM 的静态 import 会被提升，顺序不可控）。
 *
 * 2. **模块实例必须一致**。脚本自己 `import axios` 拿到的是 ESM 构建，
 *    与 Vite SSR 外部化后 request.ts 用的若不是同一份，`defaults` 不互通，
 *    adapter 替换会**静默失效**（表现为"0 次出网"，而请求其实以 Invalid URL 失败）。
 */
import axios from 'axios'
import { createPinia, setActivePinia } from 'pinia'

let statsMod: typeof import('@/stores/stats') | null = null
let elMod: typeof import('element-plus') | null = null

/** 装 adapter 并加载被测模块（顺序关键，见文件头注释） */
export async function prepare(adapter: unknown) {
  axios.defaults.adapter = adapter as never
  statsMod = await import('@/stores/stats')
  elMod = await import('element-plus')
}

/** 暴露 axios 供脚本构造符合契约的错误（adapter 失败时应抛带 config 的 AxiosError） */
export { axios }

/** 每个用例一个干净的 pinia（store 是 setup 风格，data 会跨用例残留） */
export function freshStore() {
  setActivePinia(createPinia())
  return statsMod!.useStatsStore()
}

/**
 * 记录 `ElMessage.error` 的调用，用来验证 `silent` 语义。
 *
 * 为什么要动它：`silent: true` 的契约是"失败不打扰用户"，
 * 而"弹了什么"只有这一处能观察到 —— 在 Node 里 ElMessage 真弹会直接抛
 * `document is not defined`，反而把断言变成了"崩没崩"，看不出契约是否被遵守。
 * 替换的是**对象属性**（不是模块绑定），所以对 request.ts 里 `import { ElMessage }`
 * 拿到的引用同样可见。
 */
export function spyMessage() {
  const ElMessage = elMod!.ElMessage
  const log: string[] = []
  const original = ElMessage.error
  ElMessage.error = ((msg: unknown) => {
    log.push(String(msg))
    return {} as never
  }) as typeof ElMessage.error
  return {
    log,
    restore: () => {
      ElMessage.error = original
    },
  }
}

/** 诊断用：直接打一次接口，把真实错误与堆栈带出来（store 内部会吞掉它） */
export async function probe() {
  const { statsApi } = await import('@/api/stats')
  try {
    const r = await statsApi.overview({ silent: true })
    return { ok: true, r }
  } catch (e) {
    return {
      ok: false,
      err: e instanceof Error ? `${e.name}: ${e.message}` : String(e),
      stack: e instanceof Error ? (e.stack ?? '').split('\n').slice(0, 8).join('\n') : '',
    }
  }
}
