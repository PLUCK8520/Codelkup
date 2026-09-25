-- ============================================================
-- V4: 社交与通知 —— 关注关系 / 站内通知
-- 对应 DEV-DOC-codelkup.md §9.6 / §9.7 / §13.6（Sprint 4）
-- ============================================================

-- ------------------------------------------------------------
-- t_user_follow 关注关系表
--
-- 设计要点：
--   1. uk_follow(follower_id, followee_id) —— 与 t_user_action 同思路，
--      靠**唯一索引**保证"同一个人不能重复关注同一个人"，而不是业务代码
--      "先查再插"。并发下后者有竞态窗口，前者是原子的。
--      代价同样是捕获 DuplicateKeyException，按"已关注"幂等处理。
--   2. uk_follow 的最左前缀是 follower_id，因此它**同时**支撑
--      "我关注了谁"（follower_id = ?）这个高频查询，无需另建索引。
--   3. idx_followee 支撑反向查询"谁关注了我"（粉丝列表）。
--      这一侧的索引不能省：粉丝列表是用户主页的必载数据。
--
-- 为什么把「关注」和「点赞」分成两张表而不是复用 t_user_action：
--   t_user_action 的 action_type 语义是"对**内容**的动作"（帖子/评论），
--   而关注是"对**人**的动作"，二者目标域不同（target_type 语义会打架）。
--   强行合并会让 target_id 一会儿指 t_post 一会儿指 t_user，
--   外键语义与索引选择性都会变差。分表更清晰。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_user_follow
(
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    follower_id BIGINT   NOT NULL COMMENT '关注者（动作发起方）',
    followee_id BIGINT   NOT NULL COMMENT '被关注者（对象）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
    PRIMARY KEY (id),
    -- 防重复关注 + 支撑"我关注了谁"
    UNIQUE KEY uk_follow (follower_id, followee_id),
    -- 支撑"谁关注了我"（粉丝列表）
    KEY idx_followee (followee_id, create_time DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='关注关系表';

-- ------------------------------------------------------------
-- t_notification 站内通知表
--
-- 设计要点：
--   1. **不建外键**：与全库一致（应用层保证一致性）。通知是"弱一致"数据，
--      删帖/删评论时历史通知保留即可（点进去显示"内容已删除"比 500 好）。
--   2. idx_receiver_read(receiver_id, is_read, create_time DESC) 有两个用途：
--      - 未读数：WHERE receiver_id=? AND is_read=0（等值前缀，走索引）
--      - 未读列表分页：加上 is_read=0 后仍按 create_time 倒序（索引内有序，无 filesort）
--   3. idx_receiver_time(receiver_id, create_time DESC) 支撑"全部通知"
--      按时间倒序 —— 若只用 idx_receiver_read，"不筛 is_read"的查询
--      无法利用其排序（第二列是 is_read，中间断了）。
--   4. 不做逻辑删除：通知是流水，已读即归档。需要清理时按 create_time 归档，
--      不需要 `deleted` 列参与查询（多一列会稀释索引选择性）。
--
-- content 字段说明：存**触发时的快照文案**（如"回复了你的评论"），
-- 而不是渲染好的 HTML —— 与 D4 一致，展示层永不信任存储层的 HTML。
-- 真正的跳转目标由 target_type + target_id 决定，前端自行拼链接。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_notification
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    receiver_id BIGINT       NOT NULL COMMENT '接收者',
    sender_id   BIGINT                DEFAULT NULL COMMENT '触发者（系统通知为 NULL）',
    type        TINYINT      NOT NULL COMMENT '1评论 2回复 3点赞 4关注 5采纳 6系统',
    target_type TINYINT               DEFAULT NULL COMMENT '1帖子 2评论',
    target_id   BIGINT                DEFAULT NULL COMMENT '目标ID',
    content     VARCHAR(255)          DEFAULT NULL COMMENT '快照文案（纯文本，非 HTML）',
    is_read     TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    -- 未读数 + 未读列表
    KEY idx_receiver_read (receiver_id, is_read, create_time DESC),
    -- 全部通知列表（不筛已读状态）
    KEY idx_receiver_time (receiver_id, create_time DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='站内通知表';

-- ------------------------------------------------------------
-- 种子数据说明：不种关注关系与通知数据。
-- 验收脚本会自建，避免"演示数据"干扰"未读数 = 0"这类断言。
-- ------------------------------------------------------------
