-- ============================================================
-- V12: 列表排序"id 兜底键"的索引支持
-- ============================================================
--
-- 【背景】
--   列表排序补了 `p.id DESC` 兜底键（分页正确性所需：create_time 是秒精度、
--   score 是浮点，并列时 MySQL 不保证顺序，翻页会重复或漏项）。
--   但 EXPLAIN 实测（2026-09-28，1 万行 t_post）：
--
--     ORDER BY is_top DESC, create_time DESC, id DESC
--         → Using where; Using filesort（退回读 4967 行再排序）
--     ORDER BY is_top DESC, create_time DESC
--         → Using where（纯索引序，只读 LIMIT 行）
--
--   根因：InnoDB 会给二级索引**隐式追加主键，但追加的是 ASC**；
--   ORDER BY 要求 id DESC 与隐式后缀方向相反 → 索引序无法满足排序。
--
-- 【修法：把 id DESC 显式写进索引】
--   MySQL 8 的降序索引支持混合方向；id 作为显式列写进索引后，
--   InnoDB 不再隐式追加（该列已在索引中），索引序与 ORDER BY 完全一致。
--   顺带补上 active 排序的索引 —— last_active 此前不在任何索引里，
--   `sort=active` 一直靠 filesort（属既有欠账，本轮一并还上）。
--
-- 【为什么删旧索引】
--   新索引是旧索引的"同前缀 + id DESC 后缀"，旧索引能服务的查询新索引都能服务
--   （最左前缀原则）；留着只是白付写入成本 —— 沿用 V6「方向不对就重建」的先例。
--   idx_status_time（不含 is_top）保留：仍服务其它 status + create_time 查询。
-- ============================================================

-- 全站列表：latest
ALTER TABLE t_post DROP INDEX idx_status_top_time;
CREATE INDEX idx_list_latest ON t_post (status, is_top DESC, create_time DESC, id DESC);

-- 全站列表：active（此前无索引）
CREATE INDEX idx_list_active ON t_post (status, is_top DESC, last_active DESC, id DESC);

-- 全站列表：hot（idx_hot 是 V7 为"无 id 兜底键"的排序建的，现在被同前缀 + id DESC 取代）
ALTER TABLE t_post DROP INDEX idx_hot;
CREATE INDEX idx_list_hot ON t_post (status, is_top DESC, score DESC, create_time DESC, id DESC);

-- 板块内列表：latest（带 board_id 前缀的同源索引，同样补 id DESC）
ALTER TABLE t_post DROP INDEX idx_board_status_top_time;
CREATE INDEX idx_board_list_latest
    ON t_post (board_id, status, is_top DESC, create_time DESC, id DESC);