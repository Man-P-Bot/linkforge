-- =============================================================================
-- LinkForge V1 初始化脚本
--
-- 设计依据：docs/DATABASE.md §4（字段设计要点）、§5（索引设计）、§7（失效与清理策略）
-- 迁移工具：Flyway，脚本路径 classpath:db/migration（见 linkforge-app 的 application.yml）
--
-- 硬性禁令（docs/DATABASE.md §11）：
--   本脚本及后续所有迁移脚本内**禁止** DROP TABLE、TRUNCATE、无 WHERE 的 DELETE。
--   迁移会在评审者的干净环境从零执行，也可能在已有数据的环境增量执行；
--   DROP TABLE 在前者无害、在后者是数据灾难。
--
-- 端口提示：宿主机 3306 被本机 MySQL80 占用，容器映射到 3307（docs/DECISIONS.md DR-017）。
--   容器内 / compose 网络内连 mysql:3306；宿主机客户端连 localhost:3307。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. short_link —— 短链主表（真相来源）
--
--   写少读多：跳转路径只读它（且大多数请求被缓存挡住）。
--   失效判定优先级（docs/DATABASE.md §4.2，写死不允许各处以不同顺序实现）：
--     1) status = 1 (DELETED)            -> 404
--     2) expire_at IS NOT NULL 且已到期  -> 410
--     3) 否则                            -> 302
-- -----------------------------------------------------------------------------
CREATE TABLE short_link (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部主键；统计表以 link_id 关联，不直接存短码',
    short_code  VARCHAR(8)   NOT NULL                COMMENT '短码：固定 7 位 Base62；只读不可改；自动生成与自定义共用同一唯一空间',
    target_url  VARCHAR(2048) NOT NULL               COMMENT '原始长链；创建接口拒绝超长（不截断）',
    status      TINYINT      NOT NULL DEFAULT 0      COMMENT '0=ACTIVE, 1=DELETED；用 TINYINT 不用 ENUM',
    is_custom   TINYINT      NOT NULL DEFAULT 0      COMMENT '0=自动生成, 1=自定义短码；对外体现为 API 的 custom 字段',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '由数据库生成，避免多实例时钟差异',
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后变更时间；删除只改 status，靠它回答「何时被删」',
    expire_at   TIMESTAMP    NULL     DEFAULT NULL   COMMENT '可 NULL 表示永久有效；精确到秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_code (short_code) COMMENT '短码唯一：自定义短码冲突判定 + 跳转回源查询；发号器保证无碰撞，此索引为兜底',
    KEY idx_status_code (status, short_code) COMMENT '布隆过滤器每小时全量重建的覆盖索引：WHERE status=0 只读两列，避免全表扫 2048 字节的 URL 列'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '短链主表';

-- -----------------------------------------------------------------------------
-- 2. link_stats_daily —— 统计聚合表（派生数据，保留 90 天）
--
--   按「链路 + 自然日 + 来源域」聚合；只存聚合结果，不存访问明细（PRD D-5）。
--   写入方式：定时任务每 10 秒批量 UPSERT（累加），幂等性来自 uk_link_date_referer。
--   ⚠️ 不设外键约束：统计是高频批量写，外键校验会显著抬高写入成本，
--      并让「短链被清理」与「统计行存在」互相牵制。完整性由应用层保证（docs/DATABASE.md §4.3）。
--   ⚠️ 不直接存 short_code 而存 link_id：短码编码规则若变化，历史统计无需回填。
-- -----------------------------------------------------------------------------
CREATE TABLE link_stats_daily (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    link_id        BIGINT       NOT NULL                COMMENT '关联 short_link.id；不设外键',
    stat_date      DATE         NOT NULL                COMMENT '统计的自然日',
    pv             BIGINT       NOT NULL DEFAULT 0      COMMENT '当日浏览量，累加语义（UPSERT）',
    uv_estimate    BIGINT       NOT NULL DEFAULT 0      COMMENT '当日独立访客，来源为 Redis HyperLogLog 估算值（误差约 0.81%），非精确值',
    referer_domain VARCHAR(255) NOT NULL DEFAULT 'direct' COMMENT '来源域名，只存域名；空 Referer 存 direct 不存 NULL',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该聚合行首次落库时间；stat_date 才是统计归属日',
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后累加时间，用于排查落库延迟',
    PRIMARY KEY (id),
    UNIQUE KEY uk_link_date_referer (link_id, stat_date, referer_domain) COMMENT 'UPSERT 冲突判定 + 按链路与日期范围查询；link_id 打头因为查询总是先定链路',
    KEY idx_stat_date (stat_date) COMMENT '90 天保留的按日期批量清理；与上面索引前缀不匹配，无法复用'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '统计聚合表（按天，保留 90 天）';

-- -----------------------------------------------------------------------------
-- 3. id_segment —— 号段发号表
--
--   用途：短码发号（docs/DECISIONS.md DR-011）
--     - 主路径：Redis INCR 发号
--     - 降级路径：Redis 不可用时走本表的号段分配，保证创建功能不中断
--   短码由序号 Base62 编码后左补零到固定 7 位；62^7 ≈ 3.52 万亿，无碰撞。
--   ⚠️ 已发出的号永不回收：号段取走未用完即重启只会「跳号」，短码不需要连续，只需唯一。
-- -----------------------------------------------------------------------------
CREATE TABLE id_segment (
    biz_tag    VARCHAR(64) NOT NULL                COMMENT '业务标识，便于将来多业务共用一张号段表',
    max_id     BIGINT      NOT NULL                COMMENT '当前已分配到的最大序号',
    step       INT         NOT NULL                COMMENT '号段步长，每次取一整段以减少数据库往返',
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间，用于排查发号停滞',
    PRIMARY KEY (biz_tag)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '号段发号表（Redis 不可用时的降级路径）';
