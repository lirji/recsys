-- #3 物理拆库(rec-serving 派生向量读模型库):把离线烘焙的派生向量工件
--   item_embedding / item_tower_embedding / item_semantic_id / user_embedding
-- 从共享 recsys 库物理搬到 rec-serving 自有派生库 recsys_vec。本地=同 PG 实例内独立 database;生产=独立实例。
-- 手动迁移脚本(不进 docker initdb)。
--
-- 性质:这四张是 rec-serving 的**派生读模型**(离线烘焙,非别的上下文的权威源),走"读模型复制"。
--   机制 = 专用数据源 derivedJdbc(AdShardingConfig / OfflineAdShardingConfig / 消费方 advertiser),
--   env DERIVED_PG_DB 未设 → PG_DB → recsys(默认与主库同库,行为不变);设 DERIVED_PG_DB=recsys_vec 即拆库。
--   主 @Primary 仍留 item_local / user_behavior 等读,故不整体搬,只把这四张向量表切到 derivedJdbc。
--
-- 前置(读者/烘焙者/跨读全经 derivedJdbc,已随本刀落地):
--   · owner 召回/排序/重排(VectorRecaller/SemanticRecaller/TwoTowerRecaller/PersonalizationScorer/Dpp/Mmr;
--     SemanticIdRecaller/TigerRecaller/ColdStartDetector 的向量读 surgical 切,user_behavior 读留 @Primary);
--   · 离线烘焙者(backfill-embedding/backfill-multimodal/import-tower/import-semantic-id/user-embedding)+
--     离线读者(seed-ads/lookalike/data-quality)向量读写走 derivedJdbc;
--   · 跨上下文消费方共享 derivedJdbc 直读:ad(AdRepository.loadUserVector→user_embedding、AdEmbeddingSimilarity→
--     item_embedding)、advertiser(itemEmbeddingText→item_embedding;copyEmbeddingFromItem 改 read-derivedJdbc-then-write)。
--
-- 迁移步骤(psql):
--   1) 建库 + 建表(下方 DDL;**去 REFERENCES item 外键**——item 在 content 库,跨库外键不成立):
--        CREATE DATABASE recsys_vec;  \c recsys_vec  CREATE EXTENSION IF NOT EXISTS vector;  (执行下方 DDL)
--   2) 迁数据(pg_dump 管道):
--        pg_dump -t public.item_embedding -t public.item_tower_embedding -t public.item_semantic_id \
--                -t public.user_embedding --data-only recsys | psql recsys_vec
--   3) 断开共享 recsys 库里派生表对 item 的外键(item 拆走后跨库不可执行;派生表已搬走,这步是清理旧库):
--        \c recsys
--        ALTER TABLE item_embedding       DROP CONSTRAINT IF EXISTS item_embedding_item_id_fkey;
--        ALTER TABLE item_tower_embedding DROP CONSTRAINT IF EXISTS item_tower_embedding_item_id_fkey;
--        ALTER TABLE item_semantic_id     DROP CONSTRAINT IF EXISTS item_semantic_id_item_id_fkey;
--   4) 应用切换:rec-engine / advertiser / offline 设 DERIVED_PG_DB=recsys_vec(向量读写走自有派生库);
--      offline user-embedding 的 user_behavior 读仍走 --behavior-table(behavior 侧),item_embedding/user_embedding 走 derivedJdbc。
--   5) 校验无跨库读者后,可从 recsys DROP 这四张表(本刀不删,保留回滚)。
--
-- 下面是 recsys_vec 里四张表的结构(与 01/02/03_schema 一致但去 FK):
-- \c recsys_vec
CREATE TABLE IF NOT EXISTS item_embedding (
    item_id   BIGINT PRIMARY KEY,
    embedding vector(768),
    model     TEXT
);
CREATE INDEX IF NOT EXISTS idx_item_embedding_hnsw
    ON item_embedding USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 200);

CREATE TABLE IF NOT EXISTS item_tower_embedding (
    item_id   BIGINT PRIMARY KEY,
    embedding vector(64)
);
CREATE INDEX IF NOT EXISTS idx_item_tower_hnsw
    ON item_tower_embedding USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 200);

CREATE TABLE IF NOT EXISTS item_semantic_id (
    item_id BIGINT PRIMARY KEY,
    c0 INT NOT NULL, c1 INT NOT NULL, c2 INT NOT NULL,
    model TEXT
);
CREATE INDEX IF NOT EXISTS idx_semid_prefix ON item_semantic_id (c0, c1, c2);

CREATE TABLE IF NOT EXISTS user_embedding (
    user_id   BIGINT PRIMARY KEY,
    embedding vector(768)
);
