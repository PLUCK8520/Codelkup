-- ============================================================
-- V14: 性能索引（2026-09-29 性能优化轮）
-- ============================================================
--
-- 【背景】EXPLAIN ANALYZE 实测（dev 库 1 万行 t_post）暴露四处全表扫描：
--
--   1) 列表 COUNT：WHERE status=1 AND deleted=0 → 走 idx_status_time 扫 10506 行且
--      逐行回表，25.4ms/次 —— **每个列表请求都付一次**（MyBatis-Plus 自动 COUNT）。
--   2) 相似问题标题前缀探测：title LIKE 'x%' → 无可用索引 → 无命中时 27.3ms 全扫。
--   3) /api/stats 与主页提问统计：WHERE type=? AND status=1 AND deleted=0 → 18ms 全扫。
--   4) 关注流：WHERE author_id IN (...) ORDER BY create_time DESC → 走 idx_author 后 filesort。
--
-- 【修法】纯加索引（不动查询、不改行为），逐条对应上面四处：
--   - (status, deleted)：COUNT 变覆盖索引扫描（不回表）
--   - title(32)：前缀索引服务 LIKE 'x%'（utf8mb4 下 32 字符 = 128 字节）
--   - (type, status, deleted)：提问统计走覆盖索引
--   - (author_id, create_time)：关注流免 filesort
--
-- 【为什么不用更激进的方案】（如实记录，避免下一轮重复调研）
--   - 短词 LIKE 兜底（54.9ms，全站最慢单查）不在此列：它只在"全文 0 条 + 短词"时
--     触发，且已有限流桶；收紧触发条件会改变搜索语义 —— 留待真正需要时做查询级缓存。
--   - 标签下列表 / 通知列表的 filesort 量级尚小（实测分别为 2 行 / 1 行），先不加索引，
--     避免为小表白付写入成本。
-- ============================================================

-- 1) 列表 COUNT：覆盖索引（status=1 AND deleted=0 都在索引里，不再回表）
CREATE INDEX idx_count_visible ON t_post (status, deleted);

-- 2) 相似问题：标题前缀（LIKE 'x%' 走 range，不再全表扫）
CREATE INDEX idx_title_prefix ON t_post (title(32));

-- 3) 提问统计（/api/stats + 主页）：type/status/deleted 覆盖
CREATE INDEX idx_stats_question ON t_post (type, status, deleted);

-- 4) 关注流：免 filesort（旧的 idx_author 保留，仍服务其它 author 相关查询）
CREATE INDEX idx_author_time ON t_post (author_id, create_time);