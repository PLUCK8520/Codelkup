import { del, get, post } from '@/utils/request'
import type { FollowStateVO, FollowUserVO, PageResult, PostListVO } from '@/types/api'

/**
 * 关注接口 —— 对应后端 FollowController。
 *
 * ⚠️ `follow` / `unfollow` 返回的是 **FollowStateVO**（含最新计数），
 * 而不是 void。与 S3 的点赞返回 `{active, count}` 是同一思路：
 * 一次往返同时拿到"操作后状态"和"最新计数"，前端不必再查一次。
 */
export const followApi = {
  /** 关注（幂等：重复关注不报错） */
  follow: (userId: number) => post<FollowStateVO>(`/users/${userId}/follow`),

  /** 取消关注（幂等：未关注时也不报错） */
  unfollow: (userId: number) => del<FollowStateVO>(`/users/${userId}/follow`),

  /** 关注状态与计数（游客可调用，此时 following 为 null） */
  state: (userId: number) => get<FollowStateVO>(`/users/${userId}/follow-state`),

  /** 粉丝列表（谁关注了 TA） */
  followers: (userId: number, page = 1, size = 20) =>
    get<PageResult<FollowUserVO>>(`/users/${userId}/followers`, { page, size }),

  /** 关注列表（TA 关注了谁） */
  followees: (userId: number, page = 1, size = 20) =>
    get<PageResult<FollowUserVO>>(`/users/${userId}/followees`, { page, size }),
}

/**
 * Feed 流接口 —— 对应后端 FeedController。
 *
 * "最新/热门"不走这里：它们直接复用 `GET /posts?sort=latest|hot`，
 * 后端也刻意没有为此再包一层接口（同一个查询的别名，多一层只增加维护面）。
 */
export const feedApi = {
  /** 关注流（我关注的人的帖子，时间倒序）。需登录 */
  following: (page = 1, size = 20) =>
    get<PageResult<PostListVO>>('/feed/following', { page, size }),
}
