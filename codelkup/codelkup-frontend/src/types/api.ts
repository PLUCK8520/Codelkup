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
  // 注意：公开主页**不返回 role**（L-2 修复，防管理员枚举）；本人角色见 auth store
  reputation: number
  level: number
  createTime: string
  lastActive?: string
  /** 提问数（仅公开主页返回；自己看自己时为 undefined） */
  askedCount?: number
  /** 已采纳数 */
  acceptedCount?: number
  /**
   * 采纳率（0~1 小数）。
   * ⚠️ 可能是 `null`（一次都没提问过）—— 与"0%"语义不同，展示时要区分：
   * null 显示「—」，0 才显示「0%」。
   */
  acceptRate?: number | null
}

/** 本人资料（含邮箱）—— 对应后端 CurrentUserVO */
export interface CurrentUserVO extends UserVO {
  email: string
  /** 角色：0普通 1版主 2管理员 —— 仅本人接口（/users/me）返回；公开面不暴露（L-2） */
  role: number
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

/**
 * 修改密码请求 —— 对应后端 PasswordChangeRequest。
 *
 * 必须带原密码：改密是账号接管的最后一道闸门，只凭登录态就能改密码的话，
 * 一枚被盗的 accessToken 就能把账号彻底锁成攻击者的。
 */
export interface PasswordChangeRequest {
  oldPassword: string
  newPassword: string
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

/**
 * 站点汇总统计 —— 对应后端 SiteStatsVO。
 *
 * 一次请求拿全站计数：板块 / 标签 / 提问（含解决态）。
 * 口径恒定（与调用方筛选无关），是全站数字的唯一来源。
 */
export interface SiteStatsVO {
  boardCount: number
  boardPostCount: number
  tagCount: number
  questionCount: number
  questionSolvedCount: number
  questionUnsolvedCount: number
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
  /**
   * 仅提问帖有效：true=已解决 false=待解决（对应后端 `solved`）。
   * 后端在 `type !== 1` 时会忽略它，所以前端只在「待解决」标签下才传。
   */
  solved?: boolean
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
  /** 目标为评论时（targetType=2）该评论所属的帖子ID —— 服务端解析，前端据此跳转到帖子 */
  targetPostId?: number | null
  /** 纯文本快照文案（如「张三 赞了你的帖子」） */
  content?: string
  /** 0未读 1已读 */
  isRead: number
  createTime: string
}

/**
 * 按类型的未读数 —— 对应后端 NotificationTypeUnreadVO。
 *
 * ⚠️ **只包含"有未读"的类型**：没有未读的类型不会出现在数组里，
 * 消费方要按 0 处理（不要假设 6 个类型都在）。
 */
export interface NotificationTypeUnreadVO {
  type: number
  count: number
}

/** SSE 一次性 ticket —— 对应后端 SseTicketVO（M3-2 实时通道） */
export interface SseTicketVO {
  /** 60 秒内有效、只能消费一次的短期凭证 */
  ticket: string
  expiresInSeconds: number
}

// ==================== AI（V0.3 · M3-5 ~ M3-8）====================

/** 相似问题条目 —— 对应后端 SimilarQuestionVO */
export interface SimilarQuestionVO {
  postId: number
  title: string
  /** 命中附近的一段纯文本（后端已剥离 Markdown） */
  snippet?: string | null
  boardName?: string | null
}

/** 答案引用 —— 对应后端 CitationVO。前端据 postId 生成站内链接（纯文本渲染，不走 v-html） */
export interface CitationVO {
  postId: number
  title: string
  snippet?: string | null
  boardName?: string | null
}

/** RAG 问答结果 —— 对应后端 AiAnswerVO */
export interface AiAnswerVO {
  /** 答案纯文本；降级时是可读的提示语（不是报错） */
  answer: string
  citations: CitationVO[]
  degraded: boolean
  /** NO_EVIDENCE / NO_KEY / DISABLED / BUDGET / TIMEOUT / UPSTREAM_ERROR / EMPTY_RESPONSE */
  degradeReason?: string | null
}

/** 长帖 TL;DR 结果 —— 对应后端 AiSummaryVO */
export interface AiSummaryVO {
  postId: number
  /** 3~5 条要点的纯文本；降级时为 null */
  summary?: string | null
  degraded: boolean
  degradeReason?: string | null
}

// ==================== 声望榜（V0.4）====================

/**
 * 声望榜条目 —— 对应后端 RankingUserVO。
 *
 * `rank` 由服务端给出（不是前端用下标算）：名次是榜单的核心数据，
 * 将来榜单若加分页，名次 = 偏移量 + 下标，前端自己算会错。
 */
export interface RankingUserVO {
  rank: number
  id: number
  nickname: string
  avatar?: string
  level: number
  reputation: number
  /** 最后活跃时间 */
  lastActive?: string
}

// ==================== 服务端草稿箱（V0.4 / M4-15）====================

/**
 * 草稿归属 —— 与前端 useDraft 的 scope、后端 Draft 的 scope 三处同源：
 * `new` = 新建稿；`edit:<postId>` = 某帖的编辑稿。
 */
export type DraftScope = 'new' | `edit:${number}`

/** 草稿（含正文）—— 对应后端 DraftVO */
export interface DraftVO {
  scope: string
  title: string
  content: string
  boardId?: number | null
  type: number
  tags: string[]
  /** 最后保存时间（服务端时间） */
  updateTime: string
  /**
   * 最后保存时间的 epoch 毫秒（与 updateTime 同一刻）。
   * 前端用它和本地草稿的 `updatedAt` 比较"哪份更新"——
   * 不要自己解析 updateTime 字符串：它不带时区标记，按本地时区解析会整体偏移。
   */
  updateTimeEpoch?: number
}

/** 草稿摘要（草稿箱列表）—— 对应后端 DraftSummaryVO，不含正文 */
export interface DraftSummaryVO {
  scope: string
  title: string
  /** 正文纯文本预览（后端已剥离 Markdown 语法，最多 120 字） */
  excerpt?: string | null
  boardId?: number | null
  type: number
  updateTime: string
  updateTimeEpoch?: number
}

/** 草稿保存请求 —— 对应后端 DraftSaveRequest（除 scope 外全部可空） */
export interface DraftSaveRequest {
  scope: string
  title?: string
  content?: string
  boardId?: number
  type?: number
  tags?: string[]
}

// ==================== 举报与审核（V0.5 / M5）====================

/**
 * 举报对象类型 —— 与后端 `Report.TARGET_POST / TARGET_COMMENT` 同值。
 * 只做帖子与评论两类：这是"用户能看到的、会出现在自己页面上的"两类内容。
 */
export type ReportTargetType = 1 | 2

/**
 * 举报原因枚举 —— 与后端 `ReportService.ALLOWED_REASONS` 同源。
 * 后端存的是**字符串**（不是序号），所以这里也必须发字符串：
 * 发数字会被后端判为"举报原因不合法"（40001）。
 */
export type ReportReason = 'SPAM' | 'ABUSE' | 'ILLEGAL' | 'OTHER'

/** 提交举报请求 —— 对应后端 ReportCreateRequest */
export interface ReportCreateRequest {
  targetType: ReportTargetType
  targetId: number
  reason: ReportReason
  /** 补充说明（可选，≤200 字） */
  detail?: string
}

/** 提交举报结果 —— 对应后端 ReportCreateVO */
export interface ReportCreateVO {
  id: number
  /** 0 = 待处理 */
  status: number
}

/**
 * 审核队列行 —— 对应后端 ReportAdminVO。
 *
 * ⚠️ 目标摘要字段会因目标已被删除而为空：
 *    `targetExists=false` 时 `targetTitle / targetExcerpt / targetAuthorNickname` 都是空，
 *    前端必须显示「内容已删除」而不是留白（留白会被误读成"这条举报没有目标"）。
 */
export interface ReportAdminVO {
  id: number
  targetType: ReportTargetType
  targetId: number
  /** 评论所属帖子 ID（帖子举报为 null）—— 用于跳转核对 */
  targetPostId?: number | null
  targetTitle?: string | null
  targetExcerpt?: string | null
  targetAuthorId?: number | null
  targetAuthorNickname?: string | null
  /** 目标是否仍存在；false = 已被删除 / 封禁 */
  targetExists: boolean
  /** 同一目标被举报的总次数（含已处理的举报） */
  reportedCount: number
  reporterId: number
  reporterNickname?: string | null
  reason: ReportReason | string
  detail?: string | null
  /** 0待处理 1已驳回 2已删除内容 */
  status: number
  createTime: string
  handledBy?: number | null
  handledByName?: string | null
  handledAt?: string | null
  handleNote?: string | null
}

/** 处理举报请求 —— 对应后端 ReportHandleRequest */
export interface ReportHandleRequest {
  action: 'dismiss' | 'delete_target'
  /** 处理备注（可选，≤200 字，会随举报记录留痕） */
  note?: string
}

/** 处理举报结果 —— 对应后端 ReportHandleVO */
export interface ReportHandleVO {
  id: number
  status: number
  /** 因"同目标内容已删除"被自动关闭的其它举报数 */
  closedSiblings: number
}
