import { del, get, post, put } from '@/utils/request'
import type {
  BoardVO,
  PageResult,
  PostCreateRequest,
  PostDetailVO,
  PostListVO,
  PostQueryRequest,
  PostUpdateRequest,
  TagVO,
} from '@/types/api'

/** 板块 */
export const boardApi = {
  list: () => get<BoardVO[]>('/boards'),
}

/** 标签 */
export const tagApi = {
  hot: (limit = 20) => get<TagVO[]>('/tags/hot', { limit }),
}

/** 帖子 —— 对应后端 ContentController */
export const postApi = {
  list: (query: PostQueryRequest = {}) => get<PageResult<PostListVO>>('/posts', query),

  detail: (id: number) => get<PostDetailVO>(`/posts/${id}`),

  create: (data: PostCreateRequest) => post<PostDetailVO>('/posts', data),

  update: (id: number, data: PostUpdateRequest) => put<PostDetailVO>(`/posts/${id}`, data),

  remove: (id: number) => del<void>(`/posts/${id}`),

  /**
   * 管理员置顶/加精。
   * ⚠️ 后端接口签名是 @RequestParam，所以参数走 query 而非 body
   */
  feature: (id: number, params: { top?: boolean; featured?: boolean }) =>
    post<void>(`/posts/${id}/feature?${new URLSearchParams(
      Object.entries(params)
        .filter(([, v]) => v !== undefined)
        .map(([k, v]) => [k, String(v)]),
    ).toString()}`),
}
