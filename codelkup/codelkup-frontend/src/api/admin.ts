import { get, post } from '@/utils/request'
import type {
  PageResult,
  RebuildReportVO,
  ReportAdminVO,
  ReportHandleRequest,
  ReportHandleVO,
} from '@/types/api'

/**
 * 管理员接口 —— 计数重建（InteractionController）+ 举报审核（ReportController）。
 *
 * ⚠️ 这些接口全是**破坏性写操作**（覆盖计数 / 删除内容），
 *    后端用 @PreAuthorize("hasRole('ADMIN')") 收紧到管理员；
 *    前端入口隐藏只是体验优化，不是安全边界。
 */
export const adminApi = {
  /** 重建单帖计数（driftCount 即本次修正的偏差总量） */
  rebuildPost: (postId: number) => post<RebuildReportVO>(`/admin/counters/rebuild/${postId}`),

  /** 全量重建：逐帖反查明细表校准（1 万帖量级约 20~40 秒） */
  rebuildAll: () => post<RebuildReportVO>('/admin/counters/rebuild'),

  /**
   * 审核队列。
   *
   * @param status 0待处理 / 1已驳回 / 2已删除内容；不传 = 全部
   */
  reports: (params: { status?: number; page?: number; size?: number }) =>
    get<PageResult<ReportAdminVO>>('/admin/reports', params),

  /** 待处理举报数（角标） */
  pendingReportCount: () => get<number>('/admin/reports/pending-count'),

  /**
   * 处理举报。
   *
   * action=dismiss 驳回（保留内容）/ delete_target 删除内容（复用删除路径）。
   * 已处理过的举报会返回 40910 —— 并发下另一个管理员抢先处理时会遇到。
   */
  handleReport: (id: number, payload: ReportHandleRequest) =>
    post<ReportHandleVO>(`/admin/reports/${id}/handle`, payload),
}