-- ============================================================
-- 搜索广告 schema(docs/05 §3)
-- 在 01_schema.sql 之上扩展;postgres 容器首启按文件名顺序执行(01→02)。
-- 已有库需手动执行本文件,或删除 pgdata 卷后重建。
-- mock 广告复用现有电影 item(ad.item_id → item),零额外向量依赖。
-- ============================================================

-- ---------- 广告主 ----------
CREATE TABLE IF NOT EXISTS advertiser (
    advertiser_id BIGINT PRIMARY KEY,
    name          TEXT,
    daily_budget  DOUBLE PRECISION,             -- 日预算(元)
    status        TEXT DEFAULT 'active'         -- active / paused / over_budget
);

-- ---------- 广告(创意 + 落地页 + 关联物品) ----------
CREATE TABLE IF NOT EXISTS ad (
    ad_id         BIGINT PRIMARY KEY,
    advertiser_id BIGINT REFERENCES advertiser(advertiser_id),
    item_id       BIGINT REFERENCES item(item_id),  -- 关联现有 item(复用其 embedding/特征/类目)
    title         TEXT,                             -- 创意标题(教学场景沿用电影标题)
    landing_url   TEXT,
    quality_score DOUBLE PRECISION DEFAULT 1.0,     -- 质量度(相关性/落地页,离线算;M4 先给随机基线)
    status        TEXT DEFAULT 'active',            -- active / paused
    optimization_type TEXT DEFAULT 'CPC',           -- CPC / OCPC(M6):OCPC 用 target_cpa 自动出价
    target_cpa    DOUBLE PRECISION,                 -- oCPC 目标转化成本(元/转化);仅 optimization_type=OCPC 生效
    created_at    TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ad_advertiser ON ad (advertiser_id);
CREATE INDEX IF NOT EXISTS idx_ad_item ON ad (item_id);
-- 已有库平滑升级(IF NOT EXISTS 不改既有列)
ALTER TABLE ad ADD COLUMN IF NOT EXISTS optimization_type TEXT DEFAULT 'CPC';
ALTER TABLE ad ADD COLUMN IF NOT EXISTS target_cpa DOUBLE PRECISION;

-- ---------- 竞价词(广告主买的关键词 + 匹配类型 + 出价) ----------
CREATE TABLE IF NOT EXISTS bidword (
    id         BIGSERIAL PRIMARY KEY,
    ad_id      BIGINT REFERENCES ad(ad_id),
    keyword    TEXT,
    match_type TEXT,                              -- EXACT / PHRASE / BROAD
    bid        DOUBLE PRECISION,                  -- 出价(CPC,可被 oCPC 覆盖)
    bid_mode   TEXT DEFAULT 'CPC'                 -- CPC / oCPC / oCPM
);
CREATE INDEX IF NOT EXISTS idx_bidword_keyword ON bidword (keyword);  -- 倒排:keyword → ads

-- ---------- 广告向量(query 语义匹配用;M4 直接从 item_embedding 拷贝) ----------
CREATE TABLE IF NOT EXISTS ad_embedding (
    ad_id     BIGINT PRIMARY KEY REFERENCES ad(ad_id),
    embedding vector(768),
    model     TEXT
);
CREATE INDEX IF NOT EXISTS idx_ad_embedding_hnsw
    ON ad_embedding USING hnsw (embedding vector_cosine_ops);

-- ---------- 计费 / 曝光日志(广告独有:审计 + 校准 + 结算的源) ----------
CREATE TABLE IF NOT EXISTS ad_event (
    id            BIGSERIAL PRIMARY KEY,
    request_id    TEXT,                           -- 一次搜索请求
    query         TEXT,
    user_id       BIGINT,
    ad_id         BIGINT,
    bidword_id    BIGINT,
    position      INT,                            -- 广告位次(1 基)
    event_type    TEXT,                           -- IMPRESSION / CLICK / CONVERSION
    pctr          DOUBLE PRECISION,               -- 预估 CTR(计费可复核;校准用)
    pctr_calib    DOUBLE PRECISION,               -- 校准后 CTR(进计费的值)
    ecpm          DOUBLE PRECISION,
    charged_price DOUBLE PRECISION,               -- GSP 实际扣费(次高价)
    relevance     DOUBLE PRECISION,               -- query↔ad 相关性(报表/门槛用)
    ts            TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ad_event_req ON ad_event (request_id);
CREATE INDEX IF NOT EXISTS idx_ad_event_type_ts ON ad_event (event_type, ts);
CREATE INDEX IF NOT EXISTS idx_ad_event_ad ON ad_event (ad_id, event_type);
