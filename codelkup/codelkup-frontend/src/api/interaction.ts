import { del, get, post } from '@/utils/request'
import type {
  ActionResultVO,
  CommentCreateRequest,
  CommentVO,
  RebuildReportVO,
} from '@/types/api'

/**
 * 互动接口 —— 对应后端 InteractionController。
 *
 * ⚠️ 点赞/收藏的返回值不是 void，而是 `{ active, count }`：
 * 后端把「操作后的状态」和「最新计数」一起返回，前端直接用它更新按钮，
 * 不需要再发一次详情请求。这样做的代价是响应体稍大，收益是**少一次往返**，
 * 而且避免"点赞成功后重新拉详情"造成的列表闪动。
 */
export const commentApi = {
  /** 评论列表（树形，只做两级）。游客可读 */
  list: (postId: number) => get<CommentVO[]>(`/posts/${postId}/comments`),

  /** 发表评论 / 回复。parentId 传被回复的评论 ID 即为回复 */
  create: (postId: number, data: CommentCreateRequest) =>
    post<CommentVO>(`/posts/${postId}/comments`, data),

  /** 删除评论（仅作者本人或管理员） */
  remove: (commentId: number) => del<void>(`/comments/${commentId}`),

  /** 点赞评论（幂等） */
  like: (commentId: number) => post<ActionResultVO>(`/comments/${commentId}/like`),

  /** 取消评论点赞（幂等） */
  unlike: (commentId: number) => del<ActionResultVO>(`/comments/${commentId}/like`),
}

export const postActionApi = {
  /** 点赞帖子（幂等：重复点赞计数不变） */
  like: (postId: number) => post<ActionResultVO>(`/posts/${postId}/like`),

  /** 取消点赞（幂等） */
  unlike: (postId: number) => del<ActionResultVO>(`/posts/${postId}/like`),

  /** 收藏帖子（幂等） */
  collect: (postId: number) => post<ActionResultVO>(`/posts/${postId}/collect`),

  /** 取消收藏（幂等） */
  uncollect: (postId: number) => del<ActionResultVO>(`/posts/${postId}/collect`),

  /** 采纳答案（仅楼主、仅提问帖） */
  accept: (postId: number, commentId: number) =>
    post<void>(`/posts/${postId}/accept/${commentId}`),
}

/** 计数重建（运维端点，仅管理员） */
export const counterAdminApi = {
  rebuildOne: (postId: number) =>
    post<RebuildReportVO>(`/admin/counters/rebuild/${postId}`),
  rebuildAll: () => post<RebuildReportVO>(`/admin/counters/rebuild`),
}
