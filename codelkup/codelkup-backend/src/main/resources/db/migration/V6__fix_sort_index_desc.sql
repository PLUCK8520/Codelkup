-- ============================================================
-- V6: 修正 V5 的降序索引方向
-- ============================================================
--
-- 【V5 为什么没生效】
--   V5 建的索引是 (status, is_top, create_time DESC)，其中 is_top 默认 ASC。
--   但列表页排序是 `ORDER BY p.is_top DESC, p.create_time DESC` ——
--   is_top 需要降序，索引里却是升序，排序方向不匹配。
--
--   MySQL 8.0 的降序索引正是为"混合方向排序"设计的：
--   要支持 `is_top DESC, create_time DESC`，索引必须显式写成
--   `(is_top DESC, create_time DESC)`，两列都 DESC。
--   V5 只给 create_time 写了 DESC，漏了 is_top，于是 EXPLAIN ANALYZE 里
--   仍出现 `Sort: p.is_top DESC, p.create_time DESC`（filesort），
--   仍扫 10025 行 —— 优化完全没生效。
--
-- 【实测对比（1 万行 t_post）】
--   修正前（is_top ASC）：  Sort filesort，扫 10025 行，约 20.5 ms
--   修正后（is_top DESC）： 无 filesort，只读 20 行（LIMIT），约 0.16 ms
--   → 快约 130 倍，且"只读 20 行"是结构性的（与数据量无关，随 LIMIT 恒定）
--
-- 【为什么新增 V6 而不是改 V5】
--   V5 已在环境中执行，Flyway 会校验 checksum，改 V5 会导致启动失败。
--   已执行的迁移不可修改，只能新增迁移修正。
-- ============================================================

-- 全部板块的列表：WHERE status=1 ORDER BY is_top DESC, create_time DESC
ALTER TABLE t_post DROP INDEX idx_status_top_time;
CREATE INDEX idx_status_top_time ON t_post (status, is_top DESC, create_time DESC);

-- 指定板块的列表：WHERE board_id=? AND status=1 ORDER BY is_top DESC, create_time DESC
ALTER TABLE t_post DROP INDEX idx_board_status_top_time;
CREATE INDEX idx_board_status_top_time ON t_post (board_id, status, is_top DESC, create_time DESC);
