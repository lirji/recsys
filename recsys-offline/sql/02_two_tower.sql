-- ============================================================
-- 双塔召回:item 塔向量(离线训练 train_two_tower.py 产出,ImportTowerJob 灌入)
-- 维度 64(独立于内容向量 768);user 塔向量在线由 ONNX 实时算,不落库。
-- 注:已存在 pgdata 卷不会自动重跑本文件 —— ImportTowerJob 内置 CREATE TABLE IF NOT EXISTS,
--     或手动执行:docker exec -i recsys-postgres psql -U recsys -d recsys < recsys-offline/sql/02_two_tower.sql
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS item_tower_embedding (
    item_id   BIGINT PRIMARY KEY REFERENCES item(item_id),
    embedding vector(64)
);

-- HNSW 近似最近邻,余弦距离(双塔召回 ANN 必需)
CREATE INDEX IF NOT EXISTS idx_item_tower_hnsw
    ON item_tower_embedding USING hnsw (embedding vector_cosine_ops);
