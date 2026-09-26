import { post } from '@/utils/request'
import type { RebuildReportVO } from '@/types/api'

/**
 * 管理员运维接口 —— 对应后端 InteractionController 的 /api/admin/counters/*。
 *
 * ⚠️ 这两个接口是**破坏性写操作**（会覆盖 Redis 与 MySQL 的计数），
 *    后端用 @PreAuthorize("hasRole('ADMIN')") 收紧到管理员；
 *    前端入口隐藏只是体验优化，不是安全边界。
 */
export const adminApi = {
  /** 重建单帖计数（driftCount 即本次修正的偏差总量） */
  rebuildPost: (postId: number) => post<RebuildReportVO>(`/admin/counters/rebuild/${postId}`),

  /** 全量重建：逐帖反查明细表校准（1 万帖量级约 20~40 秒） */
  rebuildAll: () => post<RebuildReportVO>('/admin/counters/rebuild'),
}