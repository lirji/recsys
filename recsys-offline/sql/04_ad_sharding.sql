-- ============================================================
-- 分库(ShardingSphere-JDBC)落地 schema(docs/05 §8;配合 recsys-advertiser/sharding.yaml)
-- 拓扑:同一 recsys-postgres 容器内两个 database —— ds_0=recsys(原库)、ds_1=recsys_ds1(新库)。
-- advertiser/ad/bidword/ad_creative 按 key 取模分到 ds_0/ds_1;其余表(含 pgvector)仍在 ds_0。
--
-- 三部分须分库执行(CREATE DATABASE 不能在事务/目标库内跑):
--   A. 在 ds_0(recsys)上:去掉跨分片不再成立的外键 + 去掉 IDENTITY(改由 ShardingSphere Snowflake 供主键)
--   B. 建 ds_1:  CREATE DATABASE recsys_ds1;   (连到 postgres/任意库执行)
--   C. 在 ds_1(recsys_ds1)上:建 4 张分片物理表(纯 bigint 主键、无外键、无 IDENTITY;主键由分片层注入)
-- 幂等:A/C 用 IF EXISTS / IF NOT EXISTS。
-- 注意:本文件只建“空的分片骨架”,不迁移 ds_0 已有的 mock 广告行(分库前的旧数据按 ad_id%2 只有半数可路由;
--       teaching 用,新数据经 recsys-advertiser 写入即按分片键正确分布。真实迁移需按分片键重分布,另起作业)。
-- ============================================================

-- ---------- A. ds_0(recsys):拆外键 + 去 IDENTITY ----------
-- ad 与 advertiser 按不同键分库(ad_id vs advertiser_id),二者可能不在同库 → 跨库外键不成立,删之。
-- ad_embedding 留单表(ds_0),但其关联的 ad 可能在 ds_1 → 外键不成立,删之。
ALTER TABLE ad           DROP CONSTRAINT IF EXISTS ad_advertiser_id_fkey;
ALTER TABLE ad           DROP CONSTRAINT IF EXISTS ad_item_id_fkey;
ALTER TABLE bidword      DROP CONSTRAINT IF EXISTS bidword_ad_id_fkey;
ALTER TABLE ad_creative  DROP CONSTRAINT IF EXISTS ad_creative_ad_id_fkey;
ALTER TABLE ad_embedding DROP CONSTRAINT IF EXISTS ad_embedding_ad_id_fkey;
-- 主键改由分片层 Snowflake 注入,去掉单库 IDENTITY(分库后跨库自增会冲突)
ALTER TABLE advertiser ALTER COLUMN advertiser_id DROP IDENTITY IF EXISTS;
ALTER TABLE ad         ALTER COLUMN ad_id         DROP IDENTITY IF EXISTS;

-- ---------- B. 建 ds_1(在 postgres 库执行)----------
-- CREATE DATABASE recsys_ds1;

-- ---------- C. ds_1(recsys_ds1):分片物理表骨架 ----------
-- 与 02_ad_schema 同结构,但:纯 bigint 主键(无 IDENTITY,主键由 Snowflake 注入)、无外键(跨库)。
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
    optimization_type TEXT DEFAULT 'CPC',
    target_cpa    DOUBLE PRECISION,
    created_at    TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ad_advertiser ON ad (advertiser_id);
CREATE TABLE IF NOT EXISTS ad_creative (
    creative_id BIGINT PRIMARY KEY,
    ad_id       BIGINT,
    title       TEXT,
    landing_url TEXT,
    status      TEXT DEFAULT 'active',
    created_at  TIMESTAMP DEFAULT now()
);
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
