-- #3 物理拆库(ad-serving 上下文):把 ad-serving 自有的 ad_event / ad_embedding / ad_servable
-- 从共享 recsys 库物理搬到 ad-serving 自有库 recsys_ad。本地=同 PG 实例内独立 database(同 recsys_behavior/recsys_content);
-- 生产=独立实例。手动迁移脚本(不进 docker initdb,因要新建 database)。
--
-- 机制:这三张 ad-serving 自有表走**专用数据源 adDbJdbc**(AdShardingConfig / OfflineAdShardingConfig),
--   AD_PG_DB 未设 → PG_DB → recsys(默认与主库同库,行为不变);设 AD_PG_DB=recsys_ad 即拆库。
--   主 @Primary 仍留共享读(item / item_embedding / user_embedding),故不整体搬主库、只搬这三张表。
--
-- 前置(先消跨上下文直读,已随本刀落地):
--   · advertiser /{id}/report 直读 ad_event → 改经 gRPC 调 ad-serving(AdReportReader,recsys.ad.report.source=grpc);
--   · 离线 11 条聚合作业读 ad_event → 改读自有读仓 ad_event_log(--ad-event-table=ad_event_log,SyncAdEventLogJob 跨库同步);
--   · ad_embedding 由 advertiser 同库 INSERT...SELECT FROM item_embedding 写 → 改经 ad-catalog-events 带向量、
--     ad-serving 消费端写自有库(AD_CATALOG_CONSUME=true;拆库后 item_embedding 不与 ad_embedding 同库);
--   · ad_servable 已是事件驱动副本(P1b),ad-serving @PostConstruct 自建 + 从 ad-catalog-events 重建,无需迁移数据。
--
-- 迁移步骤(psql 执行):
--   1) 建库 + 建表(ad_event / ad_embedding;ad_servable 由 ad-serving 自建):
--        CREATE DATABASE recsys_ad;
--        \c recsys_ad
--        CREATE EXTENSION IF NOT EXISTS vector;
--        (执行下方 ad_event / ad_embedding 建表 DDL)
--   2) 迁数据(同实例跨库无法 INSERT...SELECT,用 pg_dump 管道;ad_embedding 的 FK 早在 04_ad_sharding.sql 删除):
--        pg_dump -t public.ad_event -t public.ad_embedding --data-only recsys | psql recsys_ad
--   3) 应用切换:
--        ad-serving 设 AD_PG_DB=recsys_ad(三张表读写走自有库)+ AD_CATALOG_SOURCE=replica + AD_CATALOG_CONSUME=true
--                    (ad_servable/ad_embedding 从 ad-catalog-events 重建)+ recsys.ad.report.source 无关(它是 server 端);
--        rec-engine 设 AD_SERVING_MODE=grpc(广告在线走 ad-serving 进程);
--        advertiser 设 recsys.ad.report.source=grpc(report 经 gRPC 调 ad-serving,不再直读 ad_event)
--                    + recsys.ad.catalog.publish-events=true(发带向量的目录事件);
--        离线读 ad_event 的作业加 --ad-event-table=ad_event_log;SyncAdEventLogJob 加 --source-db=recsys_ad;
--        离线写作业(sim-ad-events/seed-ads)设 AD_PG_DB=recsys_ad(写自有库 ad_event/ad_embedding)。
--   4) 校验无跨库读者后,可从 recsys 库 DROP TABLE ad_event, ad_embedding(本刀不删,保留回滚)。
--
-- 下面是 recsys_ad 里 ad_event / ad_embedding 的表结构(与 02_ad_schema.sql 一致),供步骤 1:
-- \c recsys_ad
CREATE TABLE IF NOT EXISTS ad_event (
    id            BIGSERIAL PRIMARY KEY,
    request_id    TEXT,
    query         TEXT,
    user_id       BIGINT,
    ad_id         BIGINT,
    bidword_id    BIGINT,
    position      INT,
    event_type    TEXT,
    pctr          DOUBLE PRECISION,
    pctr_calib    DOUBLE PRECISION,
    ecpm          DOUBLE PRECISION,
    charged_price DOUBLE PRECISION,
    relevance     DOUBLE PRECISION,
    ad_bucket     TEXT,
    ts            TIMESTAMP DEFAULT now(),
    creative_id   BIGINT
);
CREATE INDEX IF NOT EXISTS idx_ad_event_req ON ad_event (request_id);
CREATE INDEX IF NOT EXISTS idx_ad_event_type_ts ON ad_event (event_type, ts);
CREATE INDEX IF NOT EXISTS idx_ad_event_ad ON ad_event (ad_id, event_type);

-- ad_embedding:768 向量 + HNSW(ANN 需全量,不分片);FK 到 ad 已删(ad 在广告主分片库,跨库)。
CREATE TABLE IF NOT EXISTS ad_embedding (
    ad_id     BIGINT PRIMARY KEY,
    embedding vector(768),
    model     TEXT
);
CREATE INDEX IF NOT EXISTS idx_ad_embedding_hnsw
    ON ad_embedding USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);
