import { post } from '@/utils/request'
import type { ReportCreateRequest, ReportCreateVO, ReportReason } from '@/types/api'

/**
 * 举报接口 —— 对应后端 ReportController 的 `POST /api/reports`。
 *
 * <h2>为什么入口要做成"自己可见性判断"</h2>
 * 后端会拦"举报自己的内容"（40001）与未登录（40101），但把这两个按钮
 * 直接摆给用户看是纯粹的打扰：自己的帖子下面是"举报"而不是"编辑"，
 * 游客点了却只得到一句"未登录"。所以前端隐藏入口、后端保留判断 ——
 * 隐藏是体验，拦截才是边界。
 *
 * <h2>重复举报不是错误</h2>
 * 同一人对同一目标第二次提交，后端返回 40909（"已经举报过了"）——
 * 这是**幂等**语义，不是失败：只能怪自己忘了已经报过。弹窗据此关闭并给一句提示。
 */

/** 举报原因选项：value 与后端枚举严格一致，label/hint 只影响展示 */
export const REPORT_REASONS: ReadonlyArray<{
  value: ReportReason
  label: string
  hint: string
}> = [
  { value: 'SPAM', label: '广告 / 垃圾信息', hint: '推广引流、刷屏、无意义内容' },
  { value: 'ABUSE', label: '辱骂 / 骚扰', hint: '人身攻击、恶意骚扰他人' },
  { value: 'ILLEGAL', label: '违法违规', hint: '涉及法律禁止的内容' },
  { value: 'OTHER', label: '其他', hint: '不属于以上三类的问题' },
]

/** 举报记录状态 —— 与后端 Report.STATUS_* 同值 */
export const REPORT_STATUS = {
  pending: 0,
  dismissed: 1,
  deletedTarget: 2,
} as const

/** 状态文案（管理端列表与提示共用，避免两处各写一套中文字面量） */
export const REPORT_STATUS_LABELS: Record<number, string> = {
  [REPORT_STATUS.pending]: '待处理',
  [REPORT_STATUS.dismissed]: '已驳回',
  [REPORT_STATUS.deletedTarget]: '已删除内容',
}

/** 原因 value → 展示文案；遇到未知枚举（后端将来新增）原样显示，不吞掉 */
export function reasonLabel(reason: string): string {
  return REPORT_REASONS.find((item) => item.value === reason)?.label ?? reason
}

export const reportApi = {
  /** 提交举报（需登录，按用户限流 20 次/小时） */
  create: (payload: ReportCreateRequest) => post<ReportCreateVO>('/reports', payload),
}