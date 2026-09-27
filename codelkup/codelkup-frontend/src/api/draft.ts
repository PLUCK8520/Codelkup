import { request } from '@/utils/request'
import type { DraftSaveRequest, DraftSummaryVO, DraftVO } from '@/types/api'

/**
 * 服务端草稿箱 —— 对应后端 DraftController（M4-15）。
 *
 * <h2>为什么全部走静默请求（silent）</h2>
 * 草稿接口的调用者有两类：
 * <ul>
 *   <li><b>编辑器自动保存</b>（高频、后台）—— 失败时编辑器的兜底是 localStorage，
 *       用户照常写作。此时弹"保存失败"没有任何可执行的补救动作，
 *       每 15 秒弹一次只会让人不敢继续打字。</li>
 *   <li><b>草稿箱页面</b>（低频、显式操作）—— 失败要看得见，但由页面自己
 *       渲染 inline 告警（与收藏页、板块页的失败态同一口径），而不是全局 toast。</li>
 * </ul>
 * 所以统一在 api 层静默，"怎么呈现失败"交给调用方决定。
 *
 * <h2>scope 直接拼进路径</h2>
 * scope 只有两种形态：{@code new} 与 {@code edit:<postId>}，冒号是 URI 路径段的
 * 合法字符（RFC 3986 pchar 包含 ":"），后端与反向代理都按原样处理 ——
 * 不做 encodeURIComponent，免得把冒号转成 %3A 后再依赖两侧的解码一致性。
 */
export const draftApi = {
  /** 草稿箱列表（摘要，不含正文），按更新时间倒序 */
  list: () => request<DraftSummaryVO[]>({ method: 'GET', url: '/drafts', silent: true }),

  /** 取一条草稿；没有草稿时返回 null（后端 data 为 null，不是错误） */
  get: (scope: string) => request<DraftVO | null>({ method: 'GET', url: `/drafts/${scope}`, silent: true }),

  /** 保存（upsert），返回库中真实落下的一条 */
  save: (data: DraftSaveRequest) =>
    request<DraftVO>({ method: 'PUT', url: '/drafts', data, silent: true }),

  /** 删除（幂等） */
  remove: (scope: string) => request<void>({ method: 'DELETE', url: `/drafts/${scope}`, silent: true }),
}