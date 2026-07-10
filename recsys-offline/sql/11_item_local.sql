-- #3 物理拆库(content 上下文):item 目录的**本地读模型副本** item_local。
-- content 上下文的权威 item 搬到自有库后(见 12_content_db.sql),rec-serving 的逐候选热路径
-- (LEXICAL 全文检索 / TAG 类目 / 冷启动 / rank category 特征)不能逐候选走 gRPC,故读**本地副本** item_local。
-- 由离线作业 sync-item-catalog 从权威 item(同库或 --source-db 跨库)全量 upsert 灌入(CDC 替身)。
-- 在线切换开关 recsys.content.item-source=replica(默认 db=读共享 item,行为不变)。
--
-- 说明:与 01_schema.sql 的 item 同构,但**只含热路径需要的列**(item_id/title/category/tags/description/popularity)
--       + title_tsv 生成列 + GIN 索引(LEXICAL 召回必需);不含 created_at(无读者)。
--       title_tsv 是 GENERATED ALWAYS(随 title/category 自算),sync 作业只灌 6 基列、不可手插该列。
CREATE TABLE IF NOT EXISTS item_local (
    item_id     BIGINT PRIMARY KEY,
    title       TEXT,
    category    TEXT,
    tags        TEXT[],
    description TEXT,
    popularity  DOUBLE PRECISION DEFAULT 0,
    title_tsv   tsvector GENERATED ALWAYS AS
                (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(category,''))) STORED
);
-- 全文检索 GIN 索引(LEXICAL/BM25 召回必需,否则全表扫描)。
CREATE INDEX IF NOT EXISTS idx_item_local_title_tsv ON item_local USING gin (title_tsv);
