import { defineStore } from 'pinia'
import { ref } from 'vue'
import { statsApi } from '@/api/stats'
import type { SiteStatsVO } from '@/types/api'

/**
 * 站点统计（全站共享的**同一份**）。
 *
 * 为什么需要这个 store：
 * `GET /api/stats` 是"全站汇总"，**与页面、分页、tab 都无关**，但它出现在四处 ——
 * 页脚（每个页面都有）、首页 Hero、问答页、标签页。没有共享层时的实际行为：
 *   · 首页 / 标签页：页脚 + 页面各一次 → 同一个 URL 请求两遍；
 *   · 问答页：更糟 —— stats 被塞进 `load()` 的 `Promise.all`，
 *     **每翻一页、每切一次 tab 都重新拉一次**，而这个接口在服务端是 3 条 COUNT
 *     且明确不缓存（见 SiteStatsServiceImpl 的注释）。
 *
 * 三条策略，缺一条都不够：
 *  1. **在途去重**：并发调用共用同一个 Promise。首屏页脚 + 页面几乎同时要数，
 *     不加这条就是两个真实请求同时飞出去。
 *  2. **TTL 复用（30s）**：翻页 / 切 tab / 在两个页面间来回跳时直接命中缓存。
 *     TTL 不设长 —— 页脚数字是给访客看的"站点还活着"的信号，几分钟前的也能接受，
 *     但没必要为了省请求把它做成"一会儿不更新"。
 *  3. **失败静默**：`silent: true` 不弹全局红条；失败时**退回已有值**（可能是 null）
 *     而不是抛异常 —— 站点统计不该拖垮任何一个页面。
 */

/** 缓存有效期：30 秒 */
const TTL_MS = 30_000

export const useStatsStore = defineStore('stats', () => {
  /** 最近一次成功拿到的统计；null = 还没拿到过 */
  const data = ref<SiteStatsVO | null>(null)

  /** 在途请求（并发去重用） */
  let inFlight: Promise<SiteStatsVO | null> | null = null
  let loadedAt = 0

  /**
   * 取统计（带缓存与在途去重）。
   *
   * @param force 跳过 TTL 强制刷新（给"想要最新数"的调用方留的口子）
   */
  function ensure(force = false): Promise<SiteStatsVO | null> {
    if (!force && data.value && Date.now() - loadedAt < TTL_MS) {
      return Promise.resolve(data.value)
    }
    if (!force && inFlight) return inFlight

    // 用局部变量持有，finally 里只清理"自己这一个"——
    // 否则一个并发调用可能把别人刚建立的 inFlight 清掉（与 request.ts 的刷新串行化同一类坑）
    let p: Promise<SiteStatsVO | null>
    p = statsApi
      .overview({ silent: true })
      .then((s) => {
        data.value = s
        loadedAt = Date.now()
        return s
      })
      .catch(() => data.value)
      .finally(() => {
        if (inFlight === p) inFlight = null
      })

    inFlight = p
    return p
  }

  return { data, ensure }
})
