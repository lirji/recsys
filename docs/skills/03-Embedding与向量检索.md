# Embedding 与向量检索

> **解决什么**:把"电影简介""用户兴趣""搜索 query"变成向量,才能算语义相似度、做向量召回。
> 本项目:`recsys-embedding` 提供可降级的 `EmbeddingClient`,pgvector 做 ANN 检索,并延伸出**双塔 / RQ-VAE 语义 ID / TIGER** 三种学习型向量。

## 1. EmbeddingClient:第三方 + 本地降级

契约 `EmbeddingClient`(`recsys-common`),**维度固定 768**(`recsys.embedding.dimension`)。两个互斥实现(`@ConditionalOnProperty recsys.embedding.provider`):

### GeminiEmbeddingClient(默认,联网)
- 调 Gemini `embedContent`,模型 `gemini-embedding-001` **默认 3072 维**,请求参数 `outputDimensionality:768` 降到 768。
- **降维后未归一化,客户端必须做 L2 归一化**再入库,否则 pgvector 余弦失准(已封装)。
- Redis 缓存 `emb:cache:{sha256}`(图片 `img:` 前缀)、重试退避、429 → `QuotaExhaustedException`(免费层 1000 次/天,次日 `--skip-existing` 续跑)。
- **熔断** `@CircuitBreaker(gemini-embedding)` → 快速失败,调用方降级到词法。

### LocalBgeEmbeddingClient(降级,`provider=local`,纯 Java CPU)
- `BgeTokenizer`(BERT WordPiece,纯 Java)+ onnxruntime 跑 `bge-base-en-v1.5`(768 维)→ CLS/mean 池化 → L2。
- 模型/vocab 在文件系统(`~/.recsys/models/...`,大不入 git,`BGE_MODEL_PATH`/`BGE_VOCAB_PATH` 可覆盖),由 `export_bge_onnx.py` 导出。
- 缺文件 → `ready=false` + 抛异常(query 理解 catch 后降级 null)。无熔断(本地)。
- `modelName="bge-base-en-v1.5-local"`——**换 provider = 换向量空间,需全量重灌**。

> **BgeTokenizer 是在线/离线契约**:BasicTokenizer(清洗/小写+NFD/CJK 切分/标点切分)+ WordpieceTokenizer(贪心 `##`,`[UNK]`),`[CLS]…[SEP]`,截断 256。与 HF BertTokenizer 对齐,类比 `SparseFeatureEncoder`。

### LlmClient(生成式,可选)
`GeminiChatClient`(`recsys.llm.enabled=true`):`generateContent` 强制 JSON 输出,缓存 `llm:cache:{sha256}`,重试退避。被 `recsys-query` 做 LLM query 理解(纠错/意图/改写)。**只有重试无熔断**(与 embedding 客户端的不对称)。

## 2. 向量怎么造

- **物品向量**:`title + category + tags + description` 拼文本 → `embedText` → `item_embedding`(`backfill-embedding`)。
- **用户向量**:近期正反馈物品向量**加权平均**(评分权重 + 半衰期衰减 + L2),`user-embedding` 作业,写 `user_embedding`。简单有效,无需训练。
- **多模态**(`backfill-multimodal`):海报图 `embedImage` 与文本向量按 `α·text+(1-α)·img` 融合,model 标 `+img`。

## 3. pgvector ANN 检索

```sql
SELECT item_id, 1 - (embedding <=> :userVec) AS sim
FROM item_embedding
ORDER BY embedding <=> :userVec     -- <=> 余弦距离
LIMIT 200;
```
- **HNSW 索引**(`vector_cosine_ops`),近似最近邻,毫秒级;不建索引会全表扫描。
- 一库到底:业务数据 + 向量同在 Postgres,少维护一个组件。
- 召回率/速度可调(`ef_search`),讲原理时可对比 IVFFlat。
- **物理拆库后**:向量表(`item_embedding`/`item_tower_embedding`/`item_semantic_id`/`user_embedding`)可迁到 `recsys_vec`(`DERIVED_PG_DB`,`derivedJdbc`),见 [16 微服务](16-微服务拆分与gRPC.md)。

## 4. 三种学习型向量(召回 → 学习/生成)

### 双塔 DSSM(TWO_TOWER 通道)
- `train_two_tower.py`:纯 ID 双塔,正 `(user,item)` 对,批内采样 softmax(+ logQ 校正,`--no-logq` 可关)。
- 产物:① `item_tower.csv`(每 item 64 维,item 塔=itemId+category embedding)→ `import-tower` 灌 `item_tower_embedding`;② `user_tower.onnx`+`tower_schema.json` 放 `recsys-recall` 资源。
- 在线 `TwoTowerRecaller`:`floorMod(userId,userBuckets)` 算 user 向量 → pgvector 余弦 ANN。item 向量离线烘焙,在线无需 item/category 词表。

### RQ-VAE 语义 ID(GENERATIVE 通道,TIGER 范式可服务版)
- `train_rqvae.py`:残差量化自编码器读 item 向量(默认双塔 64 维)→ 3 层残差量化(每层 K=256 codebook,straight-through)→ 每 item 得语义 ID `(c0,c1,c2)` → `item_semantic_id.csv` → `import-semantic-id`。
- 在线 `SemanticIdRecaller`:取用户近期正反馈 item 作种子 → 查语义 ID → 按**最长公共前缀深度**(c0/c0c1/c0c1c2)检索同簇 item。
- **为什么前缀检索而非自回归**:完整 TIGER 在线要跑自回归 Transformer + beam search,Java serving 复杂;退化为"按语义 ID 前缀找同簇",零额外在线模型、可降级。LEVELS 固定 3。

### 完整 TIGER(TIGER 通道)
- `train_tiger.py`:decoder-only Transformer,读历史语义 ID 序列自回归训练 → `tiger.onnx`。
- 在线 `TigerRecaller`:读用户历史语义 ID → beam search(beam=8)生成下一个 item 的语义 ID → 映射回 item。这是"生成式召回"的完整形态。

## 5. 踩坑

1. **维度不一致**:不同模型 768/1024/3072,混用检索错乱 → `model` 列标来源,换模型全量重灌。
2. **降维不归一化**:Gemini 降到 768 后必须客户端 L2 归一化。
3. **换 provider = 换向量空间**:Gemini 向量与本地 BGE 向量不可混用(不同语义空间)。
4. **`LocalBgeEmbeddingClient.dimension()` 返回配置的 768 而非模型 H**——对 bge-base 安全,但换模型时会掩盖不一致。
5. **API 限流**:灌库加限流 + 重试退避,429 优雅停止。

## 6. 面试要点

- **为什么 pgvector 而非专用向量库**:数据量小(百万级),一库存业务+向量,接口已抽象,升级 Milvus/Qdrant 改动小。
- **HNSW vs IVFFlat**:HNSW 图索引,查询快、内存大;IVFFlat 倒排,构建快、召回率依赖聚类。
- **用户向量为什么加权平均而非训练**:简单有效、无需 GPU,半衰期衰减体现"近期兴趣"。
- **RQ-VAE / TIGER**:把 item 量化成离散语义 ID,召回从"向量检索"走向"序列生成";前缀检索是可服务的折中。
- **降级链**:Gemini 超额/超时 → 本地 BGE(ONNX/CPU),保证离线灌库不中断。
