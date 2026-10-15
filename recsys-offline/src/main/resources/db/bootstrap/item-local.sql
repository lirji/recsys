CREATE TABLE IF NOT EXISTS item_local (
    item_id BIGINT PRIMARY KEY, title TEXT, category TEXT, tags TEXT[],
    description TEXT, popularity DOUBLE PRECISION DEFAULT 0,
    title_tsv tsvector GENERATED ALWAYS AS
        (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(category,''))) STORED);
COMMENT ON TABLE item_local IS '供在线召回与排序热路径使用的物品目录本地读模型';
CREATE INDEX IF NOT EXISTS idx_item_local_title_tsv ON item_local USING gin(title_tsv);
COMMENT ON COLUMN item_local.item_id IS '源物品身份，副本不重新生成ID';
COMMENT ON COLUMN item_local.title IS '物品标题，可空';
COMMENT ON COLUMN item_local.category IS '物品类目，可空';
COMMENT ON COLUMN item_local.tags IS '标签数组，保留原复制路径的NULL或空数组语义';
COMMENT ON COLUMN item_local.description IS '物品描述，可空';
COMMENT ON COLUMN item_local.popularity IS '源热度值，可空';
COMMENT ON COLUMN item_local.title_tsv IS '标题与类目的english全文检索生成列';
