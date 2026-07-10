-- #3 物理拆库(content 上下文):把 item 目录从共享 recsys 库搬到 content 自有库 recsys_content。
-- 本地用同一 PG 实例内的独立 database 演示 database-per-service(同 10_behavior_db.sql 的 recsys_behavior);
-- 生产则是独立实例。此文件是**手动迁移脚本**(不进 docker initdb 自动执行,因为要新建 database)。
--
-- 前置:先消跨上下文直读(已随本刀落地)——
--   · 在线展示 hydration 走 P2 的 BatchGetItems gRPC(recsys.content.serving.mode=grpc);
--   · 在线逐候选热路径(LEXICAL/TAG/冷启动/SIM/rank category/query)读本地副本 item_local(recsys.content.item-source=replica);
--   · 离线作业读本地副本 item_local(直写 SQL 作业 --item-table=item_local;ContentService 路作业 CONTENT_ITEM_SOURCE=replica)。
-- 本地副本 item_local 由 sync-item-catalog 从权威 item 灌(见 11_item_local.sql),故所有读者都不再直读 content 的 item。
--
-- 迁移步骤(在 psql 里执行):
--   1) 建库 + item 表(权威表结构,与 01_schema.sql 一致,含 title_tsv 生成列 + GIN):
--        CREATE DATABASE recsys_content;
--        \c recsys_content
--        CREATE EXTENSION IF NOT EXISTS vector;   -- item 无向量列,保留以防后续内容表也迁入
--        (执行下方 item 建表 DDL)
--   2) 迁数据(同实例跨库无法直接 INSERT...SELECT,用 pg_dump 管道;title_tsv 是生成列不导出、自动重算):
--        pg_dump -t public.item --data-only --column-inserts recsys | psql recsys_content
--      (或 COPY:pg_dump -t public.item --data-only recsys | psql recsys_content —— 生成列不在 COPY 列表里)
--   3) 断开派生向量表对 item 的外键(item 拆走后 recsys 库里 item_embedding/item_tower_embedding/item_semantic_id
--      的 REFERENCES item(item_id) 变跨库不可执行;派生表留 rec-serving,外键降为逻辑约束,由离线作业保证):
--        \c recsys
--        ALTER TABLE item_embedding        DROP CONSTRAINT IF EXISTS item_embedding_item_id_fkey;
--        ALTER TABLE item_tower_embedding  DROP CONSTRAINT IF EXISTS item_tower_embedding_item_id_fkey;
--        ALTER TABLE item_semantic_id      DROP CONSTRAINT IF EXISTS item_semantic_id_item_id_fkey;
--   4) 应用切换:
--        content-service 设 CONTENT_PG_DB=recsys_content(读写自有库);
--        离线 sync-item-catalog 加 --source-db=recsys_content(跨库 CDC 式复制权威 item → 主库 item_local);
--        rec-engine 设 recsys.content.serving.mode=grpc(展示 hydrate 走 content-service)
--                    + recsys.content.item-source=replica(热路径读 item_local);
--        离线读作业加 --item-table=item_local(直写 SQL)/ CONTENT_ITEM_SOURCE=replica(ContentService 路)。
--   5) 校验无跨库读者后(rec-serving/offline 只读 item_local、content 只读 recsys_content.item),
--      可从 recsys 库 DROP TABLE item(本刀不删,保留回滚)。
--
-- 下面是 recsys_content 里 item 的表结构(与 01_schema.sql 一致),供步骤 1 直接用:
-- \c recsys_content
CREATE TABLE IF NOT EXISTS item (
    item_id     BIGINT PRIMARY KEY,
    title       TEXT,
    category    TEXT,
    tags        TEXT[],
    description TEXT,
    popularity  DOUBLE PRECISION DEFAULT 0,
    created_at  TIMESTAMP DEFAULT now(),
    title_tsv   tsvector GENERATED ALWAYS AS
                (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(category,''))) STORED
);
CREATE INDEX IF NOT EXISTS idx_item_title_tsv ON item USING gin (title_tsv);
