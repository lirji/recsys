CREATE TABLE IF NOT EXISTS item_semantic_id (
    item_id BIGINT PRIMARY KEY,
    c0 INT NOT NULL, c1 INT NOT NULL, c2 INT NOT NULL, model TEXT);
COMMENT ON TABLE item_semantic_id IS '生成式召回模型生成的物品分层语义编码';
CREATE INDEX IF NOT EXISTS idx_semid_prefix ON item_semantic_id (c0, c1, c2);
COMMENT ON COLUMN item_semantic_id.item_id IS '物品标识，由内容库提供；派生库不建立跨库外键';
COMMENT ON COLUMN item_semantic_id.c0 IS '第一层语义码字';
COMMENT ON COLUMN item_semantic_id.c1 IS '第二层语义码字';
COMMENT ON COLUMN item_semantic_id.c2 IS '第三层语义码字';
COMMENT ON COLUMN item_semantic_id.model IS '生成语义编码的模型标识';
