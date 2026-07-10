-- P1b 广告目录事件化:广告在线服务(recsys-ad-serving)自有的"可服务副本"。
-- 由 ad-serving 消费 ad-catalog-events(广告主发布的目录快照)幂等 upsert;打破 ad-serving 直读广告主分片目录库的耦合。
-- 落在 ds_0(recsys 库,非分片),ad-serving 用主数据源直读。仅 recsys.ad.catalog.source=replica 时进读路径。
-- 注:AdServableRepository 启动时也会 CREATE TABLE IF NOT EXISTS(对已建库的实例做防御性自建),故本文件主要供 fresh init。
CREATE TABLE IF NOT EXISTS ad_servable (
    ad_id             BIGINT PRIMARY KEY,
    advertiser_id     BIGINT NOT NULL,
    item_id           BIGINT NOT NULL,
    title             TEXT,
    landing_url       TEXT,
    quality_score     DOUBLE PRECISION,
    status            TEXT,
    review_status     TEXT,
    optimization_type TEXT,
    target_cpa        DOUBLE PRECISION,
    audience_id       BIGINT,    -- A3 定向人群包(空=不定向)
    max_bid           DOUBLE PRECISION DEFAULT 0,   -- 竞价词最高出价(消费时从 bidwords 算,供 bidMap 免解析 JSON)
    servable          BOOLEAN NOT NULL DEFAULT TRUE,
    bidwords_json     TEXT,      -- [{id,keyword,matchType,bid}, ...]
    creatives_json    TEXT,      -- [{creativeId,title,landingUrl,status}, ...]
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
