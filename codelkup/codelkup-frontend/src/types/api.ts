/**
 * 与后端 DTO/VO 一一对应的类型定义。
 *
 * ⚠️ 后端返回结构变化时，必须同步改这里 —— 这是前后端契约的唯一定义处。
 *    字段名严格对齐后端 camelCase（Jackson 默认策略）。
 */

/** 统一响应结构：HTTP 状态码与业务码分离 */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}

/** 分页结果 */
export interface PageResult<T> {
  page: number
  size: number
  total: number
  pages: number
  records: T[]
}

/* ==================== 用户 ==================== */

/** 公开用户信息（不含邮箱）—— 对应后端 UserVO */
export interface UserVO {
  id: number
  nickname: string
  avatar?: string
  bio?: string
  techTags?: string
  role: number
  reputation: number
  level: number
  createTime: string
  lastActive?: string
}

/** 本人资料（含邮箱）—— 对应后端 CurrentUserVO */
export interface CurrentUserVO extends UserVO {
  email: string
  status: number
}

/** 作者简要信息 —— 对应后端 UserBriefVO */
export interface UserBriefVO {
  id: number
  nickname: string
  avatar?: string
}

/* ==================== 认证 ==================== */

export interface RegisterRequest {
  email: string
  password: string
  nickname: string
}

export interface LoginRequest {
  email: string
  password: string
}

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
  tokenType: string
  user: UserVO
}

export interface ProfileUpdateRequest {
  nickname?: string
  bio?: string
  techTags?: string
}

/* ==================== 内容 ==================== */

export interface BoardVO {
  id: number
  name: string
  slug: string
  description?: string
  icon?: string
  sortOrder: number
  postCount: number
}

export interface TagVO {
  id: number
  name: string
  useCount?: number
}

/** 帖子列表项（不含正文）—— 对应后端 PostListVO */
export interface PostListVO {
  id: number
  title: string
  summary?: string
  authorId: number
  boardId: number
  type: number
  isTop: number
  isFeatured: number
  viewCount: number
  likeCount: number
  commentCount: number
  collectCount: number
  score: number
  lastActive: string
  createTime: string
  authorNickname?: string
  authorAvatar?: string
  boardName?: string
  boardSlug?: string
  tags?: TagVO[]
  /**
   * 检索命中片段（**仅搜索接口返回**，普通列表为 undefined）。
   *
   * ⚠️ 纯文本，不含任何高亮标记 —— 标红由前端 `HighlightText` 组件完成。
   *    后端不产生 HTML，这是 D4 约定的延续。
   */
  snippet?: string
  /** 当前登录用户是否已点赞（未登录为 null） */
  liked?: boolean | null
  /** 当前登录用户是否已收藏（未登录为 null） */
  collected?: boolean | null
}

/** 帖子详情（含 Markdown 原文）—— 对应后端 PostDetailVO */
export interface PostDetailVO {
  id: number
  title: string
  /** Markdown 原文，前端渲染 + DOMPurify 消毒 */
  content: string
  summary?: string
  authorId: number
  boardId: number
  type: number
  status: number
  isTop: number
  isFeatured: number
  acceptedCommentId?: number
  viewCount: number
  likeCount: number
  commentCount: number
  collectCount: number
  lastActive: string
  createTime: string
  updateTime: string
  authorNickname?: string
  authorAvatar?: string
  boardName?: string
  boardSlug?: string
  author?: UserBriefVO
  tags?: TagVO[]
  canEdit?: boolean
  /** 当前登录用户是否已点赞。未登录时为 null —— 前端据此区分「未登录」与「已登录未点赞」 */
  liked?: boolean | null
  /** 当前登录用户是否已收藏。未登录时为 null */
  collected?: boolean | null
}

export interface PostCreateRequest {
  title: string
  content: string
  boardId: number
  type?: number
  tags?: string[]
}

export interface PostUpdateRequest {
  title?: string
  content?: string
  boardId?: number
  type?: number
  tags?: string[]
}

export interface PostQueryRequest {
  boardId?: number
  authorId?: number
  tagId?: number
  type?: number
  /** latest=最新 | hot=热度 | active=最后活跃 */
  sort?: 'latest' | 'hot' | 'active'
  page?: number
  size?: number
}

// ==================== 互动（Sprint 3）====================

/** 评论（树形）—— 对应后端 CommentVO */
export interface CommentVO {
  id: number
  postId: number
  authorId: number
  /** 纯文本，不渲染 Markdown */
  content: string
  /** 0=一级评论；非0=所挂的根评论 ID */
  parentId: number
  /** 被回复者 ID（渲染「回复 @某人」） */
  replyUserId?: number | null
  /** 点赞数（已合并 Redis 实时值） */
  likeCount: number
  isAccepted: number
  createTime: string
  authorNickname?: string
  authorAvatar?: string
  replyUserNickname?: string
  author?: UserBriefVO
  replyUser?: UserBriefVO
  /** 回复列表（仅一级评论非空） */
  replies?: CommentVO[]
  canDelete?: boolean
  liked?: boolean
}

export interface CommentCreateRequest {
  content: string
  /** 不传或 0 表示一级评论；传值表示回复该评论 */
  parentId?: number
}

/** 互动操作结果（点赞/收藏） */
export interface ActionResultVO {
  /** 操作后状态：true=已点赞/已收藏 */
  active: boolean
  /** 目标最新计数 */
  count: number
}

/** 计数重建报告（管理端） */
export interface RebuildReportVO {
  scope: string
  success: boolean
  message?: string
  beforeLike?: number
  beforeCollect?: number
  beforeComment?: number
  afterLike?: number
  afterCollect?: number
  afterComment?: number
  driftCount?: number
  totalPosts?: number
  fixedPosts?: number
}

// ==================== 检索（Sprint 4）====================

/** 搜索参数 —— 对应后端 PostSearchRequest */
export interface PostSearchRequest {
  keyword: string
  boardId?: number
  tagId?: number
  authorId?: number
  /** relevance=相关性（默认）| latest=最新 | hot=热度 */
  sort?: 'relevance' | 'latest' | 'hot'
  page?: number
  size?: number
}

// ==================== 社交（Sprint 4）====================

/** 关注/粉丝列表项 —— 对应后端 FollowUserVO */
export interface FollowUserVO {
  id: number
  nickname: string
  avatar?: string
  bio?: string
  /** 当前登录用户是否已关注 TA。未登录为 null（键存在，值为 null） */
  following?: boolean | null
}

/** 关注状态与计数 —— 对应后端 FollowStateVO */
export interface FollowStateVO {
  /** 我是否已关注 TA。null=未登录（区别于 false=登录了但没关注） */
  following?: boolean | null
  followerCount: number
  followeeCount: number
}

// ==================== 通知（Sprint 4）====================

/** 通知类型枚举（与后端 Notification.TYPE_* 对齐） */
export const NotificationType = {
  COMMENT: 1,
  REPLY: 2,
  LIKE: 3,
  FOLLOW: 4,
  ACCEPT: 5,
  SYSTEM: 6,
} as const

/** 通知列表项 —— 对应后端 NotificationVO */
export interface NotificationVO {
  id: number
  type: number
  senderId?: number | null
  senderNickname?: string
  senderAvatar?: string
  targetType?: number | null
  targetId?: number | null
  /** 纯文本快照文案（如「张三 赞了你的帖子」） */
  content?: string
  /** 0未读 1已读 */
  isRead: number
  createTime: string
}
