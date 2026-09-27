-- ============================================================
-- V10: AI 问答日志（对应 TASKS.md M3-6 / 成功标准 S8 成本与可观测）
-- ============================================================
-- ⚠️ 编号说明：原计划把这张表放在 V9，而 V9 已被 M4-15 的 t_draft 占用，故顺延为 V10。
-- 用途：每次 AI 问答留一行，回答三个问题 ——
--   ① 花了多少（token / 耗时 / 降级率）；② 谁在用（user_id）；③ 缓存到底有没有起作用（cache_hit）。
-- ⚠️ 刻意<b>不存问题原文</b>：只存指纹（SHA-256）。问题原文是用户内容，落库等于把
--    "某个用户问过什么"永久留档；而复盘需要的"同一个问题被问了几次"用指纹就能回答。
CREATE TABLE IF NOT EXISTS t_ai_answer_log
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL COMMENT '提问用户',
    question_hash  CHAR(64)     NOT NULL COMMENT '问题指纹（SHA-256 hex，不存原文）',
    citation_ids   VARCHAR(255) DEFAULT NULL COMMENT '答案引用到的帖子ID（逗号分隔）',
    answer_length  INT          NOT NULL DEFAULT 0 COMMENT '答案字符数（不含引用）',
    duration_ms    INT          NOT NULL DEFAULT 0 COMMENT '端到端耗时（含检索与调用）',
    total_tokens   INT          DEFAULT NULL COMMENT '上游返回的 token 总数；降级时为 NULL',
    cache_hit      TINYINT      NOT NULL DEFAULT 0 COMMENT '0未命中 1命中缓存',
    degraded       TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1降级',
    degrade_reason VARCHAR(32)  DEFAULT NULL COMMENT 'NO_EVIDENCE / NO_KEY / DISABLED / BUDGET / TIMEOUT / UPSTREAM_ERROR / EMPTY_RESPONSE',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, create_time),
    KEY idx_hash_time (question_hash, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='AI 问答日志';