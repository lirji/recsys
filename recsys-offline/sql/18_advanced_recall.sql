-- R9:版本化 MIND 多兴趣与 LightGCN 图召回向量。新旧版本并存，避免滚动发布时模型/向量错配。
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS multi_interest_item_embedding (
    model_version TEXT NOT NULL,
    item_id BIGINT NOT NULL,
    embedding vector(64) NOT NULL,
    PRIMARY KEY (model_version, item_id)
);
COMMENT ON TABLE multi_interest_item_embedding IS 'MIND 多兴趣召回物品向量，按模型版本隔离';
CREATE INDEX IF NOT EXISTS idx_multi_interest_item_hnsw
    ON multi_interest_item_embedding USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);

CREATE TABLE IF NOT EXISTS graph_user_embedding (
    model_version TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    embedding vector(64) NOT NULL,
    PRIMARY KEY (model_version, user_id)
);
COMMENT ON TABLE graph_user_embedding IS 'LightGCN 用户图向量，按模型版本隔离';

CREATE TABLE IF NOT EXISTS graph_item_embedding (
    model_version TEXT NOT NULL,
    item_id BIGINT NOT NULL,
    embedding vector(64) NOT NULL,
    PRIMARY KEY (model_version, item_id)
);
COMMENT ON TABLE graph_item_embedding IS 'LightGCN 物品图向量，按模型版本隔离';
CREATE INDEX IF NOT EXISTS idx_graph_item_hnsw
    ON graph_item_embedding USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);
