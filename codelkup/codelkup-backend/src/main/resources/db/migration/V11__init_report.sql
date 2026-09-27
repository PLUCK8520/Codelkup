-- ============================================================
-- V11: 举报与审核（对应 TASKS.md M5 / V0.5）
-- ============================================================
-- 用途：把"用户发现内容有问题"变成一个**可被监督、可留痕**的流程 ——
--   谁举报了谁、什么时候、依据什么原因、管理员怎么处理的（谁处理/何时/备注）。
--
-- ⚠️ uk_reporter_target 是**幂等的核心保障**（与点赞防重同一手法）：
--    同一个人对同一个对象只能有一条举报记录。靠数据库唯一索引而不是
--    "先查再写" —— 并发下"查到没有→插入"有竞态窗口，唯一索引是原子的。
--    代价是插入可能抛 DuplicateKeyException，捕获后按"已举报过"返回即可。
--
-- ⚠️ 已处理的行**不删**：举报记录是审计材料（"这条内容当初是谁以什么理由要求删除的"），
--    队列只按 status 过滤，不靠删数据来"清理"。
CREATE TABLE IF NOT EXISTS t_report
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    reporter_id BIGINT       NOT NULL COMMENT '举报人ID',
    target_type TINYINT      NOT NULL COMMENT '被举报对象类型：1帖子 2评论',
    target_id   BIGINT       NOT NULL COMMENT '被举报对象ID',
    reason      VARCHAR(32)  NOT NULL COMMENT '原因枚举：SPAM 广告 / ABUSE 辱骂骚扰 / ILLEGAL 违法违规 / OTHER 其他',
    detail      VARCHAR(200) DEFAULT NULL COMMENT '补充说明（可选，用户填写）',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 1已驳回 2已删除内容',
    handled_by  BIGINT       DEFAULT NULL COMMENT '处理人（管理员）ID',
    handled_at  DATETIME     DEFAULT NULL COMMENT '处理时间',
    handle_note VARCHAR(200) DEFAULT NULL COMMENT '处理备注（管理员填写）',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 幂等：同一人对同一对象只能举报一次
    UNIQUE KEY uk_reporter_target (reporter_id, target_type, target_id),
    -- 队列主查询：按状态过滤 + 时间倒序分页
    KEY idx_status_time (status, create_time),
    -- 支撑"这个对象一共被举报了几次"
    KEY idx_target (target_type, target_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='举报与审核记录';

-- ------------------------------------------------------------
-- 种子数据说明：不种举报数据 —— 验收脚本会自建，
-- 避免"演示数据"把队列的待处理数污染成非零（脚本要拿它做断言）。
-- ------------------------------------------------------------