-- ============================================================
-- 推荐系统数据库 schema(架构文档 §4.1)
-- postgres 容器首次启动时自动执行(挂载到 docker-entrypoint-initdb.d)
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------- 物品 ----------
CREATE TABLE IF NOT EXISTS item (
    item_id     BIGINT PRIMARY KEY,
    title       TEXT,
    category    TEXT,                       -- 类型/类目(电影:genres)
    tags        TEXT[],                     -- 标签
    description TEXT,
    popularity  DOUBLE PRECISION DEFAULT 0, -- 热度(热门召回/冷启动)
    created_at  TIMESTAMP DEFAULT now()
);

-- ---------- 物品向量(pgvector) ----------
-- 维度 768 需与 EmbeddingClient.dimension() 一致;换模型须全量重灌
CREATE TABLE IF NOT EXISTS item_embedding (
    item_id   BIGINT PRIMARY KEY REFERENCES item(item_id),
    embedding vector(768),
    model     TEXT                          -- 生成模型标识,便于换模型/灰度
);
-- HNSW 近似最近邻索引,余弦距离(向量召回必需,否则全表扫描)
CREATE INDEX IF NOT EXISTS idx_item_embedding_hnsw
    ON item_embedding USING hnsw (embedding vector_cosine_ops);

-- ---------- 用户 / 画像 ----------
CREATE TABLE IF NOT EXISTS app_user (
    user_id    BIGINT PRIMARY KEY,
    profile    JSONB,                       -- 偏好类目、标签权重等
    updated_at TIMESTAMP DEFAULT now()
);

-- ---------- 用户向量(由历史正反馈物品向量聚合) ----------
CREATE TABLE IF NOT EXISTS user_embedding (
    user_id   BIGINT PRIMARY KEY,
    embedding vector(768)
);

-- ---------- 行为日志(反馈闭环 + 训练样本来源) ----------
CREATE TABLE IF NOT EXISTS user_behavior (
    id      BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    item_id BIGINT,
    action  TEXT,                           -- impression/click/like/play/rating
    value   DOUBLE PRECISION,
    scene   TEXT,
    bucket  TEXT,                           -- AB 实验分桶
    ts      TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_behavior_user ON user_behavior (user_id, ts);
CREATE INDEX IF NOT EXISTS idx_behavior_item ON user_behavior (item_id, ts);
