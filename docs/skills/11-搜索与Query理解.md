# 搜索与 Query 理解

> **解决什么**:有 query 的场景(搜索 / 搜索广告),要先把用户输入理解成结构化意图,再让 query↔item 相关性主导链路(而非纯个性化热度)。

## 1. Query 理解流水线(`recsys-query`)

`QueryUnderstandingServiceImpl.parse` → `StructuredQuery`:
```
归一化(QueryTokens,与离线 idf 作业同源分词)
  → 可选 LLM 增强(拼写纠错 / 意图 / 改写扩展,默认关 llm.enabled=false)
  → 分词 + IDF 加权(TermWeight)
  → 意图识别(genre 命中 3.0·w + 标题投票)
  → 向量化(可选,失败降级 null)
  → 改写(同义词)
```
- `GET /api/query/parse` 调试端点。
- `QueryProperties`:maxTerms 16,intentMinScore 0.1,maxIntents 3。

## 2. IDF 词项加权(R8,`IdfWeighter`)

- 在线给每个 `TermWeight` 赋 IDF 权重：热路径先读 raw alias `idf:terms`；未见过的词形用 PostgreSQL `to_tsvector('english', raw)` 映射后查 `idf:lexemes`。两张表均 5 分钟缓存，缺失退中性 1.0。
- 离线 `idf` 作业直接从 `title_tsv` 的 canonical English lexeme 统计 document frequency → `idf:lexemes` / `idf:doc-count`；同时用 `QueryTokens` 收集 raw token 并按 PG English config 映射，双写 `idf:terms` 供兼容与热路径命中。`--min-df` 作用于 canonical df。
- `TermWeight` 经 `RecallContext.queryTerms` 透传到 LEXICAL。PostgreSQL 仍用组合 OR tsquery + `title_tsv` GIN 取候选，候选内将单词 `ts_rank_cd × (IDF-1)` 按全查询固定分母归一后叠加基础 rank；PG stopword 不进入分母。
- 全部权重为 1、Redis 缺失/OOV 或权重非法时走旧等权 FTS；query IDF 是每请求权重，不需要重建带权 tsvector。`setweight(A/B/C/D)` 解决的是文档字段权重，不是 query IDF。
- **raw 分词与 FTS stemming 各有单一真源**：raw token 统一用 `QueryTokens`；canonical lexeme/stopword/df 统一用 PostgreSQL `english`，不能用另一套 Java stemmer近似。

## 3. LLM Query 理解(可选)

`recsys.llm.enabled=true` + key 时,`recsys-query` 经 `ObjectProvider<LlmClient>` 可选注入 `GeminiChatClient` 做纠错/意图/改写(强制 JSON 输出,Redis 缓存)。未就绪则纯词法兜底。生成式增强锦上添花,不是硬依赖。

## 4. 搜索场景的融合覆盖

搜索请求由编排层用 `recsys.search.*` 覆盖默认融合,让相关性主导:
- **抬召回权重 / 压排序权重**:`recall-weight=2.0 / rank-weight=0.5`(推荐默认 1.0/1.0)。
- **channel-boost** 抬升 `SEMANTIC:2.0 + LEXICAL:2.0 + TAG:1.8`。
- **注入 3 个召回路**:`withQueryChannels` 把 SEMANTIC、LEXICAL、TAG 加入启用集。
- **`recall-fusion=rrf`**:改用 RRF 混合检索(词法 FTS + 向量),按名次融合。
- **`bypass-cold-start`**:冷用户带 query 也走 query 主导链路(不被冷启动强多样性覆盖)。
- **个性化乘子**:`persBoost = 1 + persW·max(0,cos)`(仅搜索,冷用户无向量则无影响)。

## 5. 混合检索(hybrid retrieval)

搜索的核心:**词法(精确匹配)+ 语义(向量)互补**。
- LEXICAL:Postgres `ts_rank_cd` cover-density rank over `title_tsv`，叠加 query IDF——精确、可解释,对专有名词强（它不是标准 BM25）。
- SEMANTIC:query embedding → pgvector ANN——语义泛化,对同义/近义强。
- RRF 按名次融合两路:`1/(60+rank)`,对不同打分尺度鲁棒。

## 6. 踩坑

1. **在线/离线分词不同源**——IDF 权重、意图识别全错。`QueryTokens` 是共享分词器。
2. **LLM query 理解当硬依赖**——必须可降级到纯词法(网络/额度问题)。
3. **搜索仍走个性化热度**——不覆盖融合权重的话,query 相关性会被 HOT/CF 压过,搜索体验差。
4. **冷用户带 query 被强多样性覆盖**——要 `bypass-cold-start`,否则搜索结果被打散。
5. **把 query IDF 当成 tsvector 字段权重**——静态 A/B/C/D 权重无法表达每次查询不同的 IDF；应在候选评分阶段使用 `TermWeight`。
6. ~~**忽略 FTS 词干口径**~~——已修：df 按 `title_tsv` lexeme，raw alias 和未知词形都映射到 PostgreSQL `english` canonical lexeme。

## 7. 面试要点

- **Query 理解做什么**:归一/纠错/分词/IDF 加权/意图/向量化/改写。
- **混合检索为什么**:词法精确 + 语义泛化互补,RRF 按名次融合。
- **搜索 vs 推荐融合差异**:搜索抬召回压排序、抬语义/词法 boost、可 bypass 冷启动,让相关性主导。
- **IDF 全链路**:共享分词统计 IDF，`TermWeight` 透传到 FTS 候选重排；全 1/缺表时退等权。
