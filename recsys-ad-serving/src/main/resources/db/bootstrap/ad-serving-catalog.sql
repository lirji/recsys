-- 从原防御性初始化提取；不修改已执行的历史迁移文件。
CREATE TABLE IF NOT EXISTS ad_servable (
    ad_id BIGINT PRIMARY KEY,advertiser_id BIGINT NOT NULL,item_id BIGINT NOT NULL,
    title TEXT,landing_url TEXT,quality_score DOUBLE PRECISION,status TEXT,
    review_status TEXT,optimization_type TEXT,target_cpa DOUBLE PRECISION,
    audience_id BIGINT,max_bid DOUBLE PRECISION DEFAULT 0,servable BOOLEAN NOT NULL DEFAULT TRUE,
    bidwords_json TEXT,creatives_json TEXT,updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
COMMENT ON TABLE ad_servable IS '广告在线服务消费目录事件后生成的可服务广告副本';
-- 先扩展旧版本表，再写列注释，兼容未重新初始化的本地数据库。
ALTER TABLE ad_servable ADD COLUMN IF NOT EXISTS audience_id BIGINT;
ALTER TABLE ad_servable ADD COLUMN IF NOT EXISTS max_bid DOUBLE PRECISION DEFAULT 0;
COMMENT ON COLUMN ad_servable.ad_id IS '广告标识及主键';
COMMENT ON COLUMN ad_servable.advertiser_id IS '所属广告主标识';
COMMENT ON COLUMN ad_servable.item_id IS '关联内容标识';
COMMENT ON COLUMN ad_servable.title IS '默认创意标题';
COMMENT ON COLUMN ad_servable.landing_url IS '默认落地地址';
COMMENT ON COLUMN ad_servable.quality_score IS '基础质量分';
COMMENT ON COLUMN ad_servable.status IS '快照广告状态';
COMMENT ON COLUMN ad_servable.review_status IS '快照审核状态';
COMMENT ON COLUMN ad_servable.optimization_type IS '出价优化方式';
COMMENT ON COLUMN ad_servable.target_cpa IS '目标转化成本，可空';
COMMENT ON COLUMN ad_servable.audience_id IS '定向人群标识，可空';
COMMENT ON COLUMN ad_servable.max_bid IS '快照竞价词的最高出价';
COMMENT ON COLUMN ad_servable.servable IS '可服务标记，副本仅存可服务广告';
COMMENT ON COLUMN ad_servable.bidwords_json IS '竞价词快照JSON文本';
COMMENT ON COLUMN ad_servable.creatives_json IS '创意快照JSON文本';
COMMENT ON COLUMN ad_servable.updated_at IS '副本最后应用快照时间';
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS ad_embedding(ad_id BIGINT PRIMARY KEY,embedding vector(768),model TEXT);
COMMENT ON TABLE ad_embedding IS '广告语义向量，用于查询与广告的相似度检索';
COMMENT ON COLUMN ad_embedding.ad_id IS '广告标识及主键';
COMMENT ON COLUMN ad_embedding.embedding IS '广告语义向量，768维';
COMMENT ON COLUMN ad_embedding.model IS '向量来源或模型版本';
CREATE INDEX IF NOT EXISTS idx_ad_embedding_hnsw ON ad_embedding
    USING hnsw(embedding vector_cosine_ops) WITH (m = 16, ef_construction = 200);
