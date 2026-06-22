-- 生成式召回 · RQ-VAE 语义 ID(docs/04 §14)
-- item_tower.csv(或其它 item 向量)经 train_rqvae.py 残差量化 → 每个 item 一串由粗到细的 codeword;
-- 共享前缀 = 语义相近。由 import-semantic-id 作业灌入;在线 SemanticIdRecaller 按前缀检索同簇 item。
-- 注:已存在 pgdata 卷不会自动重跑初始化,import-semantic-id 也自带 CREATE TABLE IF NOT EXISTS。
CREATE TABLE IF NOT EXISTS item_semantic_id (
    item_id BIGINT PRIMARY KEY REFERENCES item(item_id),
    c0      INT NOT NULL,   -- 第 1 层 codeword(粗簇)
    c1      INT NOT NULL,   -- 第 2 层 codeword(更细)
    c2      INT NOT NULL,   -- 第 3 层 codeword(最细)
    model   TEXT            -- 量化模型来源(便于追溯/换模型重灌)
);
-- 前缀检索索引:按 c0(粗簇)过滤候选,再按 c0c1c2 算最长公共前缀深度
CREATE INDEX IF NOT EXISTS idx_semid_prefix ON item_semantic_id (c0, c1, c2);
