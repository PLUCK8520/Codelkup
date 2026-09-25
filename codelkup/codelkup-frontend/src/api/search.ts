import { get } from '@/utils/request'
import type { PageResult, PostListVO, PostSearchRequest } from '@/types/api'

/**
 * 检索接口 —— 对应后端 SearchController。
 *
 * 后端已经处理过输入清洗（剥离 BOOLEAN 操作符、单字降级 LIKE），
 * 前端只需要把用户输入原样传过去 —— 不要在这里"预清洗"，
 * 否则会出现两套规则、两侧不一致的问题。
 */
export const searchApi = {
  /**
   * 搜索帖子（标题 + 正文）。
   *
   * 返回的 records 里可能带 `snippet`（命中片段，纯文本），
   * 用于让用户看出"这条为什么被搜出来"——尤其是命中在正文深处、
   * summary 里看不到关键词的情况。
   */
  searchPosts: (params: PostSearchRequest) =>
    get<PageResult<PostListVO>>('/search/posts', params),
}
