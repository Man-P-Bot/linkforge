-- =============================================================================
-- 阶段 4：过期清理的扫描索引
--
-- 背景：过期清理要定时扫「已过期且未删除」的短链
--   SELECT id, short_code FROM short_link
--   WHERE status = 0 AND expire_at < NOW() LIMIT 500
--
-- 为什么 V1 的索引不够（docs/TROUBLESHOOTING.md T-011 有完整实测数据）：
--   V1 只有两个索引：
--     uk_short_code (short_code)            —— 跳转路径按短码查
--     idx_status_code (status, short_code)  —— 布隆重建的覆盖索引
--
--   idx_status_code 的 status 是**等值前缀**，但 expire_at 不在索引里 →
--   MySQL 必须先取出所有 status=0 的行（1000 万行里约 1% = 10 万行），
--   **回表**读 expire_at 再过滤。
--   这是最容易被忽略的一种「看起来用了索引、实际是全表扫」的情况。
--
-- 实测对比（20 万行、2000 条已过期、真实 MySQL 8.4）：
--   idx_status_code (status, short_code)  → type=ref,  rows=97,181
--   idx_expire_status (status, expire_at) → type=range, rows=2,000
--   相差约 50 倍。放到 1000 万行的生产表上，
--   前者每轮扫约 500 万行，后者只扫 2 万行。
--
-- ⚠️ 列顺序为什么是 (status, expire_at) 而不是 (expire_at, status)：
--   两者在 MySQL 8.4 的优化器下都能被正确使用（实测都是 range），
--   但 (status, expire_at) 的语义更清晰：**「未删除的、且过期的」**。
--   status 是等值前缀、expire_at 承担范围扫描——这是范围查询索引的标准形态。
--
-- ⚠️ 为什么这个索引能跳过「已软删除」的过期行：
--   软删除后 status 变成 1，这些行**不该被反复扫描**（它们已经被清理过了）。
--   (status, expire_at) 让优化器能同时用上两个条件；
--   实测在「50% 过期行已软删除」的场景下，估算行数从 2000 降到 1。
--
-- ⚠️ 不用 ALTER TABLE ... ADD INDEX 的在线 DDL 参数：
--   MySQL 8.0+ 的 ADD INDEX 默认就是 INPLACE 且只允许并发读（LOCK=NONE），
--   不锁写。显式写 ALGORITHM=INPLACE, LOCK=NONE 是为了**把意图写死**——
--   若某个版本改变了默认行为，这里会直接报错而不是悄悄降级成 COPY 全表重建
--   （那会在 1000 万行的表上锁表数分钟）。
-- =============================================================================

ALTER TABLE short_link
    ADD KEY idx_expire_status (status, expire_at),
    ALGORITHM=INPLACE, LOCK=NONE;

-- ⚠️ 为什么 index 名字用 idx_expire_status 而不是 idx_status_expire：
--   MySQL 的索引命名没有强制约定，但**读起来要能立刻知道「前导列是什么」**。
--   本项目的既有命名是 idx_<用途>_<列>（idx_status_code = status + code），
--   沿用同一风格：idx_expire_status = 用途(expire 扫描) + 前导列(status)。
