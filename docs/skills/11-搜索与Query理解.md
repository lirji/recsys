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

- 在线给每个 `TermWeight` 赋 IDF 权重,读 Redis `idf:terms`,5 分钟缓存,缺失退中性 1.0。
- 离线 `idf` 作业:从 item 标题/类目按**与在线同源**的 `QueryTokens` 分词统计 document frequency → `idf:terms` / `idf:doc-count`(`--min-df`)。
- **在线/离线分词必须同源**——否则 IDF 对不上词,权重错乱(又一个在线离线契约)。

## 3. LLM Query 理解(可选)

`recsys.llm.enabled=true` + key 时,`recsys-query` 经 `ObjectProvider<LlmClient>` 可选注入 `GeminiChatClient` 做纠错/意图/改写(强制 JSON 输出,Redis 缓存)。未就绪则纯词法兜底。生成式增强锦上添花,不是硬依赖。

## 4. 搜索场景的融合覆盖

搜索请求由编排层用 `recsys.search.*` 覆盖默认融合,让相关性主导:
- **抬召回权重 / 压排序权重**:`recall-weight=2.0 / rank-weight=0.5`(推荐默认 1.0/1.0)。
- **channel-boost** 抬升 `SEMANTIC:2.0 + LEXICAL:2.0 + TAG:1.8`。
- **注入 3 个召回路**:`withQueryChannels` 把 SEMANTIC、LEXICAL、TAG 加入启用集。
- **`recall-fusion=rrf`**:改用 RRF 混合检索(词法 BM25 + 向量),按名次融合。
- **`bypass-cold-start`**:冷用户带 query 也走 query 主导链路(不被冷启动强多样性覆盖)。
- **个性化乘子**:`persBoost = 1 + persW·max(0,cos)`(仅搜索,冷用户无向量则无影响)。

## 5. 混合检索(hybrid retrieval)

搜索的核心:**词法(精确匹配)+ 语义(向量)互补**。
- LEXICAL:Postgres `ts_rank_cd`(BM25)over `title_tsv`——精确、可解释,对专有名词强。
- SEMANTIC:query embedding → pgvector ANN——语义泛化,对同义/近义强。
- RRF 按名次融合两路:`1/(60+rank)`,对不同打分尺度鲁棒。

## 6. 踩坑

1. **在线/离线分词不同源**——IDF 权重、意图识别全错。`QueryTokens` 是共享分词器。
2. **LLM query 理解当硬依赖**——必须可降级到纯词法(网络/额度问题)。
3. **搜索仍走个性化热度**——不覆盖融合权重的话,query 相关性会被 HOT/CF 压过,搜索体验差。
4. **冷用户带 query 被强多样性覆盖**——要 `bypass-cold-start`,否则搜索结果被打散。

## 7. 面试要点

- **Query 理解做什么**:归一/纠错/分词/IDF 加权/意图/向量化/改写。
- **混合检索为什么**:词法精确 + 语义泛化互补,RRF 按名次融合。
- **搜索 vs 推荐融合差异**:搜索抬召回压排序、抬语义/词法 boost、可 bypass 冷启动,让相关性主导。
- **IDF 在线离线同源**:分词器共享,否则权重对不上。
