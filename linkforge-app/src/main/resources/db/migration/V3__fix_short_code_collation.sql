-- =============================================================================
-- LinkForge V3 —— short_code 排序规则改为 ascii_bin（大小写敏感）
--
-- 背景（docs/DECISIONS.md DR-040）：
--   V1 建表时 short_code 继承了表默认的 utf8mb4_0900_ai_ci（_ci = case-insensitive），
--   但短码空间是 Base62（0-9A-Za-z），**同时使用大小写字母**。
--   于是 `0Q1A`（序号 100016）与 `0Q1a`（序号 100042）在 ci 比较下是同一个值，
--   uk_short_code 判定冲突，发号器重试 3 次后返回 503（LINKFORGE-503-01）。
--
--   实测（2026-10-15，D6 造数）：序号区间 100000~100065 共 66 个短码，
--   ci 去重后仅 42 个，**24 组冲突（约 36%）**。这不是边缘情况，而是常态——
--   只要序号递增跨过大小写字母边界就必然踩到。
--
--   这与 API.md §5.2「短码大小写敏感，Abc123 与 abc123 是两个不同短码」
--   和 DR-025 的前提直接矛盾：契约要求敏感，schema 却不敏感。
--
-- 决策：改为 ascii_bin
--   1. Base62 是**纯 ASCII**，1 字节/字符。utf8mb4 要 4 字节/字符，
--      对短码这种 8 字节的小字段是 4 倍存储浪费。
--   2. ascii_bin 是**二进制比较**，语义精确：短码不该有任何 Unicode 语义
--      （不区分重音、不做等价归一），A 与 a 就是两个不同的字节。
--   3. 索引体积：idx_status_code 是覆盖索引（status, short_code），
--      short_code 从 utf8mb4 的 32 字节降到 8 字节，该索引每行省 24 字节。
--
--   为什么不用 utf8mb4_0900_as_cs：它的大小写敏感语义是对的，
--   但仍按 utf8mb4 存 4 字节/字符，对纯 ASCII 数据没有收益。
--
-- 迁移安全性：
--   - 本脚本只做 ALTER TABLE ... MODIFY COLUMN，不含 DROP / TRUNCATE /
--     无 WHERE 的 DELETE（遵守 V1 头部禁令）。
--   - 改排序规则不改变已有数据字节，只是**比较方式**变了——
--     原本被错误拒绝的记录不会「变成重复」，因为校验已在应用侧做过。
--   - MODIFY 会自动重建 uk_short_code 与 idx_status_code。
--     数据量大时耗时随表增长；第 1 周表仅 9 行，第 5 周前表规模仍可控。
-- =============================================================================

ALTER TABLE short_link
    MODIFY COLUMN short_code VARCHAR(8)
        CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL COMMENT '短码：固定 7 位 Base62；只读不可改；自动生成与自定义共用同一唯一空间';

-- ⚠️ 只改 short_code，不动 target_url：
--    target_url 存的是用户提交的原始 URL，**可能含中文路径与查询参数**
--    （如 https://example.com/搜索?q=中文），需要完整的 Unicode 支持。
--    且它不参与任何唯一性判定，大小写不敏感对它是**正确**语义
--    （同一个 URL 的主机名部分本就不区分大小写）。
--    若把它也改成 ascii_bin，含中文的 URL 会直接插入失败。