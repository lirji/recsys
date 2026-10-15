CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS item_tower_embedding (
    item_id BIGINT PRIMARY KEY, embedding vector(64));
COMMENT ON TABLE item_tower_embedding IS '双塔召回模型生成的物品侧向量';
CREATE INDEX IF NOT EXISTS idx_item_tower_hnsw
    ON item_tower_embedding USING hnsw (embedding vector_cosine_ops);
COMMENT ON COLUMN item_tower_embedding.item_id IS '物品标识，由内容库提供；派生库不建立跨库外键';
COMMENT ON COLUMN item_tower_embedding.embedding IS '双塔模型生成的物品向量；新建表默认64维';
