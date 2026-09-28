-- ============================================================
-- V13：重建 t_post 全文索引，去掉 InnoDB 停用词过滤
-- ------------------------------------------------------------
-- 背景（2026-09-28 实测取证，隔离实验在全新表上复现）：
--   MySQL ngram 分词器的停用词过滤会把"所有双字组都含字母 a"的词整个吞掉
--   （默认停用词表含单字母 "a"）：java(ja/av/va)、kafka(ka/af/fa)、
--   data(da/at/ta) 这类词 MATCH 恒 0 条 —— 实测 LIKE '%Java%' 命中 142 条，
--   而 MATCH('Java') 0 条；关闭 innodb_ft_enable_stopword 后立刻恢复。
--
--   停用词属性是"建索引时记账"的（见 MySQL 手册 innodb_ft_enable_stopword），
--   所以必须【关着停用词重建一次】—— 只改配置不会改变已有索引的行为。
--
--   ⚠️ 用 SET SESSION 而不是 SET GLOBAL：不需要 SUPER 权限，且只影响本次
--      重建，不改变实例的全局行为（业务账号没有 SYSTEM_VARIABLES_ADMIN）。
--   ⚠️ 与 docker-compose.yml 的 --innodb-ft-enable-stopword=OFF 互补：
--      配置项管"今后所有新建索引"，本迁移管"已经建错的这一条"。
--      （app 层还有第二道保险：SearchService 的"全文 0 条短词兜底 LIKE"。）
-- ============================================================

SET SESSION innodb_ft_enable_stopword = OFF;

ALTER TABLE t_post DROP INDEX ft_title_content;

ALTER TABLE t_post ADD FULLTEXT KEY ft_title_content (title, content) WITH PARSER ngram;