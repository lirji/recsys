-- ============================================================
-- 分库 ds_1(recsys_ds1)自动引导 —— 补齐 04_ad_sharding.sql 里"手动分库"的 B/C 两段,让容器化一键栈可用。
--
-- 背景:04_ad_sharding.sql 把 `CREATE DATABASE recsys_ds1` 与 ds_1 分片骨架注释成"人工分库执行"
--       (CREATE DATABASE 不能在事务/目标库内跑)。但 `docker compose --profile apps` 全链路容器化时,
--       advertiser(写侧 @Primary)、ad-serving / rec-engine(读侧次数据源)都经 ShardingSphere 连 ds_1,
--       库不存在则连接池初始化失败、启动即挂。故本文件在 postgres 首次初始化(docker-entrypoint-initdb.d,
--       仅空卷执行一次,按文件名序在 04 之后)自动建库 + 骨架。
--
-- 幂等:CREATE DATABASE 用 \gexec 条件建;表用 IF NOT EXISTS。手动重跑亦安全,不影响已初始化的库。
-- 骨架与 04_ad_sharding.sql 的 C 段逐字一致(纯 bigint 主键、无 IDENTITY、无外键;主键由 Snowflake 注入)。
-- 说明:psql 元命令(\gexec / \connect)只作用于本文件所在的 psql 会话,不影响后续 05+ 初始化脚本(仍连 recsys)。
-- ============================================================

-- ---------- 建 ds_1 ----------
SELECT 'CREATE DATABASE recsys_ds1'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'recsys_ds1')\gexec

-- ---------- 切到 ds_1,建 4 张分片物理表 ----------
\connect recsys_ds1

CREATE TABLE IF NOT EXISTS advertiser (
    advertiser_id BIGINT PRIMARY KEY,
    name          TEXT,
    daily_budget  DOUBLE PRECISION,
    status        TEXT DEFAULT 'active'
);
CREATE TABLE IF NOT EXISTS ad (
    ad_id         BIGINT PRIMARY KEY,
    advertiser_id BIGINT,
    item_id       BIGINT,
    title         TEXT,
    landing_url   TEXT,
    quality_score DOUBLE PRECISION DEFAULT 1.0,
    status        TEXT DEFAULT 'active',
    -- A2 创意审核 / A3 智能定向:与 02_ad_schema.sql 的 ad 列对齐(分片表两库结构必须一致,
    -- 否则路由到 ds_1 的广告 INSERT 直接报列不存在——曾在存量卷上实际踩中)。
    review_status TEXT DEFAULT 'approved',
    review_reason TEXT,
    optimization_type TEXT DEFAULT 'CPC',
    target_cpa    DOUBLE PRECISION,
    audience_id   BIGINT,
    created_at    TIMESTAMP DEFAULT now()
);
-- 存量 ds_1 平滑升级(IF NOT EXISTS 不改既有列;口径同 02_ad_schema.sql)
ALTER TABLE ad ADD COLUMN IF NOT EXISTS review_status TEXT DEFAULT 'approved';
ALTER TABLE ad ADD COLUMN IF NOT EXISTS review_reason TEXT;
ALTER TABLE ad ADD COLUMN IF NOT EXISTS optimization_type TEXT DEFAULT 'CPC';
ALTER TABLE ad ADD COLUMN IF NOT EXISTS target_cpa DOUBLE PRECISION;
ALTER TABLE ad ADD COLUMN IF NOT EXISTS audience_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_ad_advertiser ON ad (advertiser_id);
CREATE TABLE IF NOT EXISTS ad_creative (
    creative_id BIGINT PRIMARY KEY,
    ad_id       BIGINT,
    title       TEXT,
    landing_url TEXT,
    status      TEXT DEFAULT 'active',
    review_status TEXT DEFAULT 'approved',   -- A2:与 02_ad_schema.sql 对齐
    review_reason TEXT,
    created_at  TIMESTAMP DEFAULT now()
);
ALTER TABLE ad_creative ADD COLUMN IF NOT EXISTS review_status TEXT DEFAULT 'approved';
ALTER TABLE ad_creative ADD COLUMN IF NOT EXISTS review_reason TEXT;
CREATE INDEX IF NOT EXISTS idx_ad_creative_ad ON ad_creative (ad_id);
CREATE TABLE IF NOT EXISTS bidword (
    id         BIGINT PRIMARY KEY,
    ad_id      BIGINT,
    keyword    TEXT,
    match_type TEXT,
    bid        DOUBLE PRECISION,
    bid_mode   TEXT DEFAULT 'CPC'
);
CREATE INDEX IF NOT EXISTS idx_bidword_keyword ON bidword (keyword);
CREATE INDEX IF NOT EXISTS idx_bidword_ad ON bidword (ad_id);
