-- ============================================================
-- V9: 服务端草稿箱（对应 TASKS.md M4-15）
-- ============================================================
-- 背景：M2-10 的草稿只存 localStorage（ADR-6 口径：当时需求是"防误关"，
-- 不是"多端同步"）。本轮用户点名要做服务端草稿箱，补齐两件本地做不到的事：
--   1) 换设备/换浏览器后仍能接着写（localStorage 做不到）
--   2) 清掉浏览器数据后草稿还在（localStorage 做不到）
-- 两套存储的关系：localStorage 仍是"本机写前日志"（离线兜底、无网也能存），
-- 服务端行是"跨设备副本"；进入编辑器时取两者中较新的一份恢复。

-- ------------------------------------------------------------
-- t_draft 草稿表
-- ------------------------------------------------------------
-- ⚠️ 刻意不建 t_post_tag 那种标签关联表：草稿期的标签只是"用户待发布的意图"，
--    还没成为一个正式内容，标签实体（t_tag 的 use_count 反范式计数）不该被草稿污染。
--    标签以 JSON 数组字符串暂存本列，发布时再走 PostService 的正式写入路径。
CREATE TABLE IF NOT EXISTS t_draft
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '草稿ID',
    user_id     BIGINT       NOT NULL COMMENT '归属用户',
    scope       VARCHAR(64)  NOT NULL COMMENT '草稿归属：new=新建稿；edit:<postId>=某帖的编辑稿',
    title       VARCHAR(120) NOT NULL DEFAULT '' COMMENT '标题（草稿允许只写了一半）',
    content     MEDIUMTEXT   NOT NULL COMMENT '正文（Markdown 原文，与 t_post 同口径）',
    board_id    BIGINT       DEFAULT NULL COMMENT '所选板块（可为 NULL：草稿允许"还没选"）',
    type        TINYINT      NOT NULL DEFAULT 0 COMMENT '0普通帖 1提问帖',
    tags        VARCHAR(512) DEFAULT NULL COMMENT '标签名 JSON 数组（草稿期不建关联）',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- ⚠️ 唯一索引承担「同一用户同一 scope 只保留一条」的职责，
    --    写法上仍走 INSERT ... ON DUPLICATE KEY UPDATE（靠数据库约束而不是"先查再写"，
    --    后者在并发下会插入两条并撞唯一键）
    UNIQUE KEY uk_user_scope (user_id, scope),
    KEY idx_user_update (user_id, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='服务端草稿箱';