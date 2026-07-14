-- 16 · item 类目/热度 btree 索引(在线 TAG/HOT 热路径提速)。
--
-- 背景:item 表原先只有 title_tsv 的 GIN 索引,category/popularity 无 btree。于是:
--   · TAG 召回 byCategories:  SELECT ... WHERE category IN (...) ORDER BY popularity DESC LIMIT k
--   · HOT 兜底 hotByPopularity: SELECT ... ORDER BY popularity DESC LIMIT k
-- 只能走 Seq Scan + Top-N Sort。TAG 几乎每个非冷启动请求都跑 → 规模化后是纯粹的延迟隐患。
--
-- 幂等:CREATE INDEX IF NOT EXISTS。已存在 pgdata 卷不会重跑 01_schema.sql/11_item_local.sql,
-- 故本文件供**已有库**手动补建:
--   docker compose -f docker/docker-compose.yml exec -T postgres psql -U recsys -d recsys < recsys-offline/sql/16_item_indexes.sql
-- (item_local 仅在 recsys.content.item-source=replica 且已建该表时需要;未建则第二组语句忽略即可。)

-- 权威 item
CREATE INDEX IF NOT EXISTS idx_item_category_pop ON item (category, popularity DESC);
CREATE INDEX IF NOT EXISTS idx_item_popularity ON item (popularity DESC);

-- 本地副本 item_local(content 拆库 replica 模式;表不存在时可忽略本段报错)
CREATE INDEX IF NOT EXISTS idx_item_local_category_pop ON item_local (category, popularity DESC);
CREATE INDEX IF NOT EXISTS idx_item_local_popularity ON item_local (popularity DESC);
