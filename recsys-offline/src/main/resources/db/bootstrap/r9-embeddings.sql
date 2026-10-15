CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS multi_interest_item_embedding(
    model_version TEXT NOT NULL,item_id BIGINT NOT NULL,embedding vector(64) NOT NULL,
    PRIMARY KEY(model_version,item_id));
CREATE TABLE IF NOT EXISTS graph_user_embedding(
    model_version TEXT NOT NULL,user_id BIGINT NOT NULL,embedding vector(64) NOT NULL,
    PRIMARY KEY(model_version,user_id));
CREATE TABLE IF NOT EXISTS graph_item_embedding(
    model_version TEXT NOT NULL,item_id BIGINT NOT NULL,embedding vector(64) NOT NULL,
    PRIMARY KEY(model_version,item_id));
CREATE INDEX IF NOT EXISTS idx_multi_interest_item_hnsw ON
    multi_interest_item_embedding USING hnsw(embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_graph_item_hnsw ON
    graph_item_embedding USING hnsw(embedding vector_cosine_ops);

COMMENT ON TABLE multi_interest_item_embedding IS 'MIND多兴趣召回物品向量，按模型版本发布';
COMMENT ON COLUMN multi_interest_item_embedding.model_version IS '训练产物模型版本';
COMMENT ON COLUMN multi_interest_item_embedding.item_id IS '物品标识';
COMMENT ON COLUMN multi_interest_item_embedding.embedding IS '64维近似L2归一化物品向量';
COMMENT ON TABLE graph_user_embedding IS '图召回用户向量，按模型版本发布';
COMMENT ON COLUMN graph_user_embedding.model_version IS '训练产物模型版本';
COMMENT ON COLUMN graph_user_embedding.user_id IS '用户标识';
COMMENT ON COLUMN graph_user_embedding.embedding IS '64维近似L2归一化用户向量';
COMMENT ON TABLE graph_item_embedding IS '图召回物品向量，按模型版本发布';
COMMENT ON COLUMN graph_item_embedding.model_version IS '训练产物模型版本';
COMMENT ON COLUMN graph_item_embedding.item_id IS '物品标识';
COMMENT ON COLUMN graph_item_embedding.embedding IS '64维近似L2归一化物品向量';
