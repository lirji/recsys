CREATE TABLE IF NOT EXISTS ad_event_log (
    id BIGINT PRIMARY KEY, request_id TEXT, query TEXT, user_id BIGINT, ad_id BIGINT,
    bidword_id BIGINT, position INT, event_type TEXT, pctr DOUBLE PRECISION,
    pctr_calib DOUBLE PRECISION, ecpm DOUBLE PRECISION, charged_price DOUBLE PRECISION,
    relevance DOUBLE PRECISION, ad_bucket TEXT, ts TIMESTAMP, creative_id BIGINT);
COMMENT ON TABLE ad_event_log IS '供离线分析使用的广告事件读仓';
CREATE INDEX IF NOT EXISTS idx_ad_event_log_type_ts ON ad_event_log(event_type, ts);
CREATE INDEX IF NOT EXISTS idx_ad_event_log_ad ON ad_event_log(ad_id, event_type);
CREATE INDEX IF NOT EXISTS idx_ad_event_log_req ON ad_event_log(request_id);
COMMENT ON COLUMN ad_event_log.id IS '源事件ID及增量水位';
COMMENT ON COLUMN ad_event_log.request_id IS '原请求标识';
COMMENT ON COLUMN ad_event_log.query IS '原搜索词';
COMMENT ON COLUMN ad_event_log.user_id IS '原用户身份，可空';
COMMENT ON COLUMN ad_event_log.ad_id IS '原广告身份，可空';
COMMENT ON COLUMN ad_event_log.bidword_id IS '原竞价词身份，可空';
COMMENT ON COLUMN ad_event_log.position IS '原展示位置，可空';
COMMENT ON COLUMN ad_event_log.event_type IS '原事件类型';
COMMENT ON COLUMN ad_event_log.pctr IS '原预估CTR，可空';
COMMENT ON COLUMN ad_event_log.pctr_calib IS '原校准CTR，可空';
COMMENT ON COLUMN ad_event_log.ecpm IS '原排序收益，可空';
COMMENT ON COLUMN ad_event_log.charged_price IS '原事件记录价格，保留既有浮点类型';
COMMENT ON COLUMN ad_event_log.relevance IS '原相关性，可空';
COMMENT ON COLUMN ad_event_log.ad_bucket IS '原广告实验桶';
COMMENT ON COLUMN ad_event_log.ts IS '源事件时间，保留原TIMESTAMP微秒';
COMMENT ON COLUMN ad_event_log.creative_id IS '原创意身份，可空';
