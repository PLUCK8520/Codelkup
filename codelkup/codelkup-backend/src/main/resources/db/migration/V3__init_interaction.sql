-- ============================================================
-- V3: 互动 —— 评论 / 点赞收藏记录
-- 对应 DEV-DOC-codelkup.md §9.4 / §9.5 / §13.5（Sprint 3）
-- ============================================================

-- ------------------------------------------------------------
-- t_comment 评论表
--
-- 只做二级：parent_id = 0 表示一级评论，非 0 表示"回复某条评论"。
-- 无限嵌套在前端渲染与递归查询上都是灾难，且实际体验无提升
-- （参考 https://news.ycombinator.com 也是两级）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_comment
(
    id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '评论ID',
    post_id       BIGINT   NOT NULL COMMENT '帖子ID',
    author_id     BIGINT   NOT NULL COMMENT '作者ID',
    content       TEXT     NOT NULL COMMENT '评论内容（纯文本，不渲染 Markdown）',
    parent_id     BIGINT   NOT NULL DEFAULT 0 COMMENT '根评论ID：0=一级评论，非0=该评论的回复',
    reply_user_id BIGINT            DEFAULT NULL COMMENT '被回复者ID（用于渲染「回复 @某人」）',
    like_count    INT      NOT NULL DEFAULT 0 COMMENT '点赞数（冗余基线，Redis 为准）',
    is_accepted   TINYINT  NOT NULL DEFAULT 0 COMMENT '是否被楼主采纳（仅问答帖有意义）',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    -- 支撑"取某帖的全部评论并按时间排序"——这是最高频的查询
    KEY idx_post (post_id, create_time),
    KEY idx_author (author_id),
    -- 支撑"按 parent_id 聚合出树形"：一次取回某帖所有评论后，
    -- 在应用层分组即可，无需递归查库
    KEY idx_parent (parent_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='评论表';

-- ------------------------------------------------------------
-- t_user_action 用户互动记录表（点赞 / 收藏统一表）
--
-- ⚠️ uk_user_target_action 是**防重复点赞的核心保障**：
--    靠数据库唯一索引而非业务代码"先查再写"来保证幂等。
--    理由：并发场景下"查了不存在→插入"有竞态窗口（两个请求同时查到
--    "未点赞"，然后都插入），唯一索引是原子的，不存在这个窗口。
--    代价是插入可能抛 DuplicateKeyException，捕获后按"已点赞"处理即可。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_user_action
(
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL COMMENT '操作者ID',
    target_type TINYINT  NOT NULL COMMENT '1帖子 2评论',
    target_id   BIGINT   NOT NULL COMMENT '目标ID',
    action_type TINYINT  NOT NULL COMMENT '1点赞 2收藏',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 唯一索引防重：同一用户对同一目标同一动作只能有一条记录
    UNIQUE KEY uk_user_target_action (user_id, target_type, target_id, action_type),
    -- 支撑"某目标有多少人点赞"→ COUNT(*) 反查，计数重建脚本依赖此索引
    KEY idx_target (target_type, target_id, action_type),
    -- 支撑"我点赞过的帖子列表"
    KEY idx_user_type (user_id, target_type, action_type, create_time DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='用户互动记录表（点赞/收藏）';

-- ------------------------------------------------------------
-- 给 t_post 补「回复数」：列表页要显示讨论热度，但只用 comment_count
-- 会数错（含二级回复）。二者语义不同：
--   comment_count = 全部评论数（含回复）
--   reply_count   = 仅回复数（parent_id != 0）
-- 不引入新列，列表页直接展示 comment_count 即可，此处不加冗余列。
-- ------------------------------------------------------------

-- ------------------------------------------------------------
-- 种子数据说明：不种评论与互动数据。
-- 验收脚本会自建，避免"演示数据"污染计数对账。
-- ------------------------------------------------------------
