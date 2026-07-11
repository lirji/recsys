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
    created_at  TIMESTAMP DEFAULT now(),
    -- 全文检索向量(标题+类目),供 LEXICAL 词法召回(BM25/ts_rank);生成列,随 title/category 自动更新
    title_tsv   tsvector GENERATED ALWAYS AS
                (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(category,''))) STORED
);
-- 全文检索 GIN 索引(LEXICAL 召回必需)
CREATE INDEX IF NOT EXISTS idx_item_title_tsv ON item USING gin (title_tsv);
-- 类目 + 热度 btree:TAG 召回 byCategories(WHERE category IN (...) ORDER BY popularity DESC LIMIT k)
-- 直接 index-only 取 Top-N、免排序;否则每次(几乎每个非冷启动请求)全表 Seq Scan + Top-N Sort。
CREATE INDEX IF NOT EXISTS idx_item_category_pop ON item (category, popularity DESC);
-- 纯热度:HOT 兜底 hotByPopularity(ORDER BY popularity DESC)与 fusion 的 pop-debias。
CREATE INDEX IF NOT EXISTS idx_item_popularity ON item (popularity DESC);
-- 已有库平滑升级(已存在 pgdata 卷不会重跑本文件)
ALTER TABLE item ADD COLUMN IF NOT EXISTS title_tsv tsvector GENERATED ALWAYS AS
    (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(category,''))) STORED;

-- ---------- 物品向量(pgvector) ----------
-- 维度 768 需与 EmbeddingClient.dimension() 一致;换模型须全量重灌
CREATE TABLE IF NOT EXISTS item_embedding (
    item_id   BIGINT PRIMARY KEY REFERENCES item(item_id),
    embedding vector(768),
    model     TEXT                          -- 生成模型标识,便于换模型/灰度
);
-- HNSW 近似最近邻索引,余弦距离(向量召回必需,否则全表扫描)。
-- m/ef_construction 显式给值:提升召回图连通度与构建质量(默认 m=16/ef_construction=64)。
-- ⚠️ 运行期 ef_search 决定单次检索的候选宽度,默认仅 40 —— 当召回 LIMIT(如 200)> ef_search 时,
--    HNSW 只保证约 40 个真近邻、其余为低质量填充,召回被静默腰斩。在线连接须 SET hnsw.ef_search ≥ 最大 LIMIT
--    (见 rec-engine application.yml 的 hikari.connection-init-sql)。
-- 注:已存在 pgdata 卷不会重跑本文件;要应用新参数需手动 DROP INDEX 后重建。
CREATE INDEX IF NOT EXISTS idx_item_embedding_hnsw
    ON item_embedding USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);

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
    position INT,                           -- IMPRESSION 展示位次(1 基);曝光日志闭环 + PAL 位置去偏
    ts      TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_behavior_user ON user_behavior (user_id, ts);
CREATE INDEX IF NOT EXISTS idx_behavior_item ON user_behavior (item_id, ts);
-- 已看过滤热点(SeenItemsFilter:每次推荐必跑 SELECT DISTINCT item_id WHERE user_id=? AND action IN(...))
-- 覆盖索引 (user_id, action, item_id) 让该查询走 index-only,免回堆;随重度用户历史增长仍稳定。
CREATE INDEX IF NOT EXISTS idx_behavior_user_action_item ON user_behavior (user_id, action, item_id);
-- 已有库平滑升级(已存在 pgdata 卷不会重跑本文件)
ALTER TABLE user_behavior ADD COLUMN IF NOT EXISTS position INT;
