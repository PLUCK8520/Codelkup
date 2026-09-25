-- ============================================================
-- V2: 内容核心 —— 板块 / 标签 / 帖子 / 帖子-标签关联
-- 对应 DEV-DOC-codelkup.md §9.3 / §13.4（Sprint 2）
-- ============================================================

-- ------------------------------------------------------------
-- t_board 板块表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_board
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '板块ID',
    name        VARCHAR(50)  NOT NULL COMMENT '板块名',
    slug        VARCHAR(50)  NOT NULL COMMENT 'URL 短名（english，用于路由）',
    description VARCHAR(255) DEFAULT NULL COMMENT '板块描述',
    icon        VARCHAR(50)  DEFAULT NULL COMMENT '图标标识',
    sort_order  INT          NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    post_count  INT          NOT NULL DEFAULT 0 COMMENT '帖子数（冗余，读多写少）',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1隐藏',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_slug (slug),
    KEY idx_sort (status, sort_order)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='板块表';

-- ------------------------------------------------------------
-- t_tag 标签表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_tag
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '标签ID',
    name        VARCHAR(30)  NOT NULL COMMENT '标签名（展示用，保留大小写）',
    use_count   INT          NOT NULL DEFAULT 0 COMMENT '被引用次数',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- ⚠️ 用 utf8mb4_unicode_ci（大小写不敏感）保证 "Java" 与 "java" 视为同一标签，
    --    避免出现两个仅大小写不同的标签。展示时保留首次创建时的大小写。
    UNIQUE KEY uk_name (name),
    KEY idx_use_count (use_count)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='标签表';

-- ------------------------------------------------------------
-- t_post 帖子表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_post
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '帖子ID',
    title               VARCHAR(120) NOT NULL COMMENT '标题',
    content             MEDIUMTEXT   NOT NULL COMMENT '正文（Markdown 原文，不存渲染后 HTML，见修订 D4）',
    summary             VARCHAR(255) DEFAULT NULL COMMENT '摘要（列表页用，发帖时生成）',
    author_id           BIGINT       NOT NULL COMMENT '作者ID',
    board_id            BIGINT       NOT NULL COMMENT '板块ID',
    type                TINYINT      NOT NULL DEFAULT 0 COMMENT '0普通帖 1提问帖',
    status              TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审 1已发布 2已删 3已封禁',
    is_top              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否置顶',
    is_featured         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否加精',
    accepted_comment_id BIGINT       DEFAULT NULL COMMENT '采纳的评论ID',
    view_count          INT          NOT NULL DEFAULT 0 COMMENT '浏览数（Redis 计数）',
    like_count          INT          NOT NULL DEFAULT 0 COMMENT '点赞数（Redis 计数）',
    comment_count       INT          NOT NULL DEFAULT 0 COMMENT '评论数（Redis 计数）',
    collect_count       INT          NOT NULL DEFAULT 0 COMMENT '收藏数（Redis 计数）',
    score               DOUBLE       NOT NULL DEFAULT 0 COMMENT '热度分（定时算）',
    last_active         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后活跃时间',
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_author (author_id),
    KEY idx_board_time (board_id, create_time DESC),
    KEY idx_score (score DESC),
    KEY idx_status_time (status, create_time DESC),
    FULLTEXT KEY ft_title_content (title, content) WITH PARSER ngram
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='帖子表';

-- ------------------------------------------------------------
-- t_post_tag 帖子-标签关联
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_post_tag
(
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    post_id     BIGINT   NOT NULL COMMENT '帖子ID',
    tag_id      BIGINT   NOT NULL COMMENT '标签ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- ⚠️ 唯一索引同时承担「防重复关联」职责（与点赞防重同一思路：靠数据库约束而非业务判断）
    UNIQUE KEY uk_post_tag (post_id, tag_id),
    KEY idx_tag (tag_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='帖子-标签关联表';

-- ------------------------------------------------------------
-- 初始化板块数据（个人项目，板块固定十级，直接种入）
-- ------------------------------------------------------------
INSERT INTO t_board (name, slug, description, icon, sort_order)
VALUES ('综合讨论', 'general', '技术闲聊、行业观察、职业发展', 'chat', 10),
       ('后端开发', 'backend', 'Java / Go / Python 服务端开发', 'server', 20),
       ('前端开发', 'frontend', 'JavaScript / TypeScript / 框架与工程化', 'layout', 30),
       ('算法与数据结构', 'algorithm', '刷题、算法思路、复杂度分析', 'binary-tree', 40),
       ('人工智能', 'ai', '大模型应用、机器学习、提示工程', 'sparkles', 50),
       ('数据库', 'database', 'MySQL / Redis / 存储选型与优化', 'database', 60),
       ('运维与部署', 'devops', 'Docker / CI-CD / 服务器与监控', 'terminal', 70),
       ('求职与面试', 'career', '秋招春招、面经、简历与 offer 交流', 'briefcase', 80),
       ('开源项目', 'opensource', '开源推荐、参与经验、项目共建', 'git-branch', 90),
       ('反馈建议', 'feedback', '站务反馈、功能建议、bug 上报', 'message-square', 100)
ON DUPLICATE KEY UPDATE name = VALUES(name);
