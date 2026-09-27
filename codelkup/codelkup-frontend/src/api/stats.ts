import { get } from '@/utils/request'
import type { SiteStatsVO } from '@/types/api'

/**
 * 站点汇总统计 API。
 *
 * 为什么单独一个文件而不是并进 post.ts：它横跨板块 / 标签 / 帖子三类资源，
 * 并进任何一个都会让那个文件的职责变糊（与后端 StatsController 单独成类的理由一致）。
 */
export const statsApi = {
  /** 全站计数：板块 / 标签 / 提问（含解决态）。游客可读 */
  overview: () => get<SiteStatsVO>('/stats'),
}