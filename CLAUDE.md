# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在本仓库中处理代码时提供指导。

## 这是什么

一个面向生产的推荐系统脚手架：Java + Spring Boot 3.2，Maven 多模块单仓库。它实现了经典的 **召回 → 排序 → 重排** 漏斗，并通过离线/近线链路（embedding、协同过滤、模型训练）向在线存储灌入数据。设计为可通过 `docker compose` 完全本地运行，但其结构便于后续拆分为微服务。

**当前状态：核心漏斗已实现并在本地验证（M1–M3）。** 召回（12 个通道：vector/i2i/hot/tag + u2u/swing/semantic/cold + two_tower + generative + lexical + tiger——two_tower 是 DSSM 风格的学习型召回：item 塔离线烘焙进 pgvector，user 塔在线通过 ONNX 运行；generative 是 RQ-VAE 语义 ID 召回(TIGER 范式可服务版)：离线把 item 量化成 codeword 元组灌 `item_semantic_id`，在线按用户近期 item 的语义 ID 前缀检索同簇，见 docs/04 §14；lexical 是词法/BM25 召回(Postgres 全文检索 `ts_rank_cd` over `item.title_tsv`)，搜索场景与 semantic 向量路经 RRF 混合检索，见 docs/04 §11;tiger 是**完整 TIGER 自回归生成召回**(`train_tiger.py` 训 decoder-only Transformer→`tiger.onnx`,在线 `TigerRecaller` 读历史语义 ID→beam search 生成下一个 item 的语义 ID→映射回 item,见 docs/04 §14.1),与 generative(前缀检索版)并存）、排序（rule + LightGBM→ONNX + DeepFM + MMoE 多任务/ESMM + DIN 序列）、可插拔重排（diversity/mmr/none）、分层 A/B 实验（recall×rank×rerank，确定性分桶，曝光记录到 `user_behavior.bucket`）、冷启动（检测器 + 兴趣引导）均已就位。离线任务向在线存储灌入数据。`docs/` 中的设计文档是事实来源——修改行为时务必同步更新。

## 构建与运行

```bash
# JDK 21（pom 中设置 java.version=21）
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

mvn clean install                          # 构建所有模块
mvn -pl recsys-common install              # 构建单个模块（加 -am 则连同其依赖）
mvn -pl recsys-rec-engine spring-boot:run  # 运行某个服务（仅限 [app] 模块）
mvn -pl <module> test                      # 测试单个模块
mvn -pl <module> -Dtest=ClassName#method test   # 运行单个测试

docker compose up -d                       # postgres(pgvector) + redis；首次启动时自动应用 schema
docker compose --profile full up -d        # 同时启动 kafka + nacos（可选组件）

cp .env.example .env                        # 然后填入 GEMINI_API_KEY 等（.env 已被 gitignore——切勿提交）
```

**离线任务** 不是独立的 main——`recsys-offline` 是一个 Spring Boot 应用，其 `JobRunner` 根据 `--job=<name>` 参数进行分发（每个任务是一个以其 `name()` 为键的 `OfflineJob` bean）：

```bash
mvn -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments=--job=import-items
```

任务名（大致按此顺序运行以引导初始化各存储）：`import-items`、`import-behavior`、`backfill-embedding`、`user-embedding`、`item-cf`、`user-cf`、`swing`、`hot`、`build-features`、`gen-samples`、`gen-samples-mt`（多任务 + 行为序列样本 → `train/samples_mt.csv`，一个喂给 MMoE/DIN 的**独立**文件；见下文）、`import-tower`（从 `train/item_tower.csv` 加载双塔 item 向量到 `item_tower_embedding`；会自建该表）、`import-semantic-id`（从 `train/item_semantic_id.csv` 加载 RQ-VAE 语义 ID 到 `item_semantic_id`，供生成式召回；会自建该表，先跑 `train_rqvae.py`）、`gen-samples-impr`（**曝光日志闭环**：从真实 `IMPRESSION` 日志 + 后续正反馈归因构造 `samples_mt.csv`，得真实负样本 + 真实位次喂 PAL；as-of 无穿越；与 `gen-samples-mt` 同契约、择一使用）、`sim-rec-events`（**教学用**：造带位置偏置的曝光+点击写 `user_behavior`，让闭环 + PAL 可演示；`--clear`/`--sessions`/`--slate`/`--decay`）。不带 `--job` 运行时会打印可用任务集并退出。**评估任务**（在各存储构建完成后运行）：`eval`（离线推荐质量——按时间切分的留出正样本 → 复用在线 recall→rank 流水线 → Precision/Recall/NDCG/MAP/MRR/HitRate/Coverage/Diversity/Novelty @K；支持 `--k=10,20,50`、`--recall-only`（逐通道召回）、`--rank-strategy=v1|onnx|deepfm`、`--max-users`、`--threads`（逐用户并行评估，默认 = CPU 核数）；写出 `eval/metrics-<ts>.csv`；`--print-split` 只打印时间切分点 splitTs 供严格 eval 用）和 `ab-report`（基于 `IMPRESSION`/点击曝光日志的在线逐桶 CTR；写出 `eval/ab-report-<ts>.csv`）。**严格 eval**（消除乐观偏置，docs/04 §9 #4）：行为派生作业 `item-cf`/`user-cf`/`swing`/`hot`/`user-embedding` 支持 `--max-ts=<epochSeconds>`，只用切分点前的行为聚合(共享 `BehaviorQuery`，口径同 `AsOfFeatureBuilder`)；`bash recsys-offline/run_strict_eval.sh` 一键完成"取 splitTs→`--max-ts` 重建无泄漏存储→eval→自动用全量重建恢复"(实测 pipeline 偏置仅 ±1~2%;TWO_TOWER/SEMANTIC 伪 query 为残余,见 docs/04 §9 #4)。**搜索广告作业**（docs/05，需先建 `02_ad_schema.sql`）：`seed-ads`（造 mock 广告主/广告/竞价词，从现有电影 item 复用创意与 `item_embedding`，并写 Redis 竞价词倒排 `bidword:inv:{keyword}`；`--clear` 重置；`--ads`/`--advertisers`/`--seed`）、`sim-ad-events`（**教学用**广告曝光/点击模拟，刻意制造"pctr 系统性高估"给校准喂数据；非真实流量）、`ad-calibrate`（用 `ad_event` 拟合 pCTR 保序回归 PAVA → Redis `ad:calib:{model}`；`--model`/`--bins`）、`ad-report`（按广告位次聚合曝光/点击/转化/收入/eCPM/CTR/CVR/相关性，写 `eval/ad-report-<ts>.csv`）、`ad-ocpc`（**oCPC 反馈控制**：用 `ad_event` 算各广告主"实际 CPA"，`k_new=clamp(k_old·(目标CPA/实际CPA)^alpha, min, max)` → Redis `ad:ocpc:{adv}`，在线 `OcpcBidder` 出价 `bid=targetCpa·pCVR·k`；`--days`/`--alpha`/`--min`/`--max`）。

**排序模型（`recsys-offline/train/`）**——两个训练器喂给在线 ONNX 链路，由 `recsys.rank.strategy`（`RANK_STRATEGY` 环境变量）选择，二者在模型缺失/失败时均回退到规则打分：
- `train_lgbm.py` → `model.onnx`（LightGBM，`strategy=onnx`）：单一稠密输入 `[N,5]`（即 5 个 `FeatureAssembler` 特征）。
- `train_deepfm.py` → `model_deepfm.onnx` + `rank_schema.json` + `category_vocab.json`（PyTorch DeepFM，`strategy=deepfm`）：**双输入** `dense[N,5]` float + `sparse[N,3]` int64（userId/itemId/category embedding）。在线 `DeepFmRankService` 通过 `SparseFeatureEncoder` 编码稀疏 id，其分桶（`floorMod`）+ category 词表必须与训练器完全一致——这是 embedding 的在线/离线契约，类比于稠密特征的 `FeatureAssembler`。**两个训练器共用一个 `samples.csv`**（gen-samples 输出稠密特征 + 原始 `user_id,item_id,category`）；`train_lgbm` 会忽略原始 id 列。**gen-samples 默认按 as-of（时间点）方式计算特征**，通过 `AsOfFeatureBuilder` 实现——它按 ts 顺序流式处理 ratings，并严格在应用该事件**之前**对每个样本的特征做快照，因此不会有未来/标签信息泄漏到训练中（无需 Redis 的 `feat:*`）。传入 `--leaky` 则改为读取全历史 `feat:*` 聚合（旧行为，仅为评估对比“修复泄漏前/后”的 A/B 而保留；需先运行 `build-features`）。DeepFM 使用**随机**训练/验证切分（id-embedding 模型需要训练集见过每个 id），因此其 AUC 不能与 LightGBM 的时间切分 AUC 比较——请用 `eval` 任务做公平的排序对比。DeepFM 需要 `pip install -r requirements-deepfm.txt`（torch + onnxscript）；导出器被强制为单个自包含的 `.onnx`（无外部 `.data`），因为 Java 从 classpath 以字节数组方式加载它。重新训练后，执行 **`mvn -pl recsys-rank clean install`** 以将模型重新打包进 jar（单纯 `install` 会在 `target/classes` 中留下陈旧资源）。
- `train_mmoe.py` → `model_mmoe.onnx` + `mmoe_schema.json` + `mmoe_category_vocab.json`（PyTorch **MMoE 多任务 + ESMM**，`strategy=mmoe`）：与 DeepFM 相同的双输入，但**双输出** `ctr[N,1]` + `cvr[N,1]`。在 `samples_mt.csv`（不是 `samples.csv`）上训练，该文件由 `gen-samples-mt` 输出，带**两个标签**——`label_click`（是否有过任何交互）和 `label_like`（评分 ≥4）；负样本是按热度采样的未交互项。ESMM 损失 = `BCE(pCTR, click) + BCE(pCTR·pCVR, like)`，在整个空间上计算（CVR 塔从不直接看到“曝光未点击”，从而消除 CVR 的样本选择偏差）。在线 `MmoeRankService` 读取两个 head 并融合为 `score = pCTR·(cvrBias + cvrWeight·pCVR)`（权重在 `recsys.rank.multi-task` 中，无需重训即可调；`cvrBias=0,cvrWeight=1` ⇒ pCTCVR）。
- `train_din.py` → `model_din.onnx` + `din_schema.json` + `din_category_vocab.json`（PyTorch **DIN 行为序列 + MMoE head**，`strategy=din`）：**四输入** `dense[N,5]` + `sparse[N,3]` + `seq[N,L]`（item 分桶）+ `seq_len[N]`，双输出 ctr/cvr。候选 item 对用户历史做 target-attention（候选与序列**共享 item embedding 表**），padding 位置通过 `seq_len` 掩码（空序列 ⇒ pooled 向量强制为 0，因此冷用户不会得到垃圾值）。`gen-samples-mt` 为每个样本输出 as-of 行为序列（最近 ≤20 个评分 ≥4 的 item，在当前事件**之前**做快照 = 时间点快照，无泄漏）。在线 `DinRankService` 在请求时**通过 JdbcTemplate** 拉取用户最近 ≤`seqLen` 个评分 ≥4 的 item（每个用户查询一次，在候选间广播），由 `SequenceEncoder` 编码，其固定长度 + 右侧 padding + item 分桶必须与 `train_din.py` 一致（序列的在线/离线契约，类比于 `SparseFeatureEncoder`）。MMoE 与 DIN 二者：随机切分、双输出 ONNX、与 DeepFM 相同的 IR9 / 自包含 `.onnx` 注意事项；二者在模型缺失时均回退到规则打分。`eval` 任务接受 `--rank-strategy=mmoe|din`。注意评估的 ground-truth（评分 ≥4 的留出正样本）**等同于 DeepFM 的单一标签**，因此 DeepFM 在 NDCG/precision 上领先，而多任务模型在 HitRate@20 / coverage / diversity / novelty 以及离线 AUC 上领先——这是真正的多目标权衡，而非退化。`run_eval_compare.sh` 在同一 ground truth 上运行全部五种策略。**位置偏差去偏(PAL,docs/04 §13)**:`train_mmoe.py`/`train_din.py` 的 CTR head 训练时叠加位置偏置塔 `b(position)`(吃 `gen-samples-mt` 多输出的 `position` 列),**导出 ONNX 时 `position=None` 该塔不入图** —— 线上输入/编码器/契约一概不变(PAL 相对"位置当特征+预测置0"的关键优势:在线零改动)。`position=0`=位次未知(`gen-samples-mt` 评分派生样本无真实曝光位次 → 默认全 0,PAL 休眠、精度等同未去偏)。**真实位次走曝光日志闭环**(docs/04 §15):`ExposureLogger` 记 `user_behavior.position`,`gen-samples-impr` 从曝光日志造样本(真负样本+真位次)→ PAL 激活(已本地实测:relevance 头 AUC 0.44→0.56、导出仍 dense+sparse 双输入);`sim-rec-events` 为教学模拟器;`gen-samples-mt --position-proxy` 则用热度名次代理(仅教学)。

**召回模型（`train/train_two_tower.py`）**——一个纯 ID 的双塔 / DSSM，用于*学习型*召回（通道 `TWO_TOWER`），与基于内容的 `VECTOR` 通道互补。在 `samples.csv` 中的正向 `(user_id, item_id)` 对上训练（仅用 label/id/category 列，因此 as-of 与 leaky 无关），采用批内采样 softmax。输出：(1) `item_tower.csv`——每个 item 的 64 维向量（item 塔 = itemId+category embedding），由 `import-tower` 任务加载到 `item_tower_embedding`；(2) `user_tower.onnx` + `tower_schema.json`，放入 **`recsys-recall`** 的资源（不是 rank 的）——即 user 塔（userId-bucket embedding）。在线 `TwoTowerRecaller` 加载 `user_tower.onnx`，为 `user_bucket = floorMod(userId, user_buckets)` 计算查询向量（与训练相同的取模；`user_buckets` 来自 schema），然后在 `item_tower_embedding` 上做 pgvector 余弦 ANN。item 向量在离线烘焙好，因此在线无需 item/category 词表。与 DeepFM 相同的 ONNX 导出注意事项（IR9，单个自包含文件）。重新训练后，执行 **`mvn -pl recsys-recall clean install`** 以重新打包 `user_tower.onnx`。优雅降级：模型缺失 → 通道返回空，其他通道兜底。逐通道 `eval --recall-only` 显示 `TWO_TOWER` 在学习型通道中居首（注意：在含测试正样本的整个周期上训练，因此绝对数值偏乐观——与 CF/vector 通道相同的注意事项）。

**生成式召回模型（`train/train_rqvae.py`，docs/04 §14）**——TIGER 范式的可服务版，通道 `GENERATIVE`。**RQ-VAE**（残差量化自编码器）读 item 向量（默认 `item_tower.csv` 双塔 64 维，`--input` 可换内容 embedding）→ 3 层残差量化（每层 K=256 codebook，straight-through）→ 每个 item 得语义 ID `(c0,c1,c2)` 写 `train/item_semantic_id.csv`，由 `import-semantic-id` 任务灌入 `item_semantic_id` 表（需先建 `03_semantic_id.sql` 或由任务自建）。在线 `SemanticIdRecaller`（`recsys-recall`）取用户近期正反馈 item 作种子 → 查语义 ID → 按**最长公共前缀深度**（c0/c0c1/c0c1c2）检索同簇 item。**为什么是前缀检索而非自回归生成**：完整 TIGER 在线要跑自回归 Transformer + beam search，Java serving 复杂；本版退化为“按语义 ID 前缀找同簇”，零额外在线模型、可降级。LEVELS 固定 3（与表 c0/c1/c2 + recaller 对齐）。优雅降级：表缺失/无种子 → 通道空，其他路兜底。

数据库 schema 位于 `recsys-offline/sql/01_schema.sql`，由 postgres 容器在首次启动时自动执行（挂载到 `docker-entrypoint-initdb.d`）。若要重新执行，删除 `pgdata` 卷。

> 注意：仓库当前没有自动化测试；上面的 `mvn ... test` 行是添加测试时应遵循的约定。

## 架构

**模块布局**——`[app]` = 可运行服务（端口），`[lib]` = 计算/领域库（无可执行 jar）：

- `recsys-common` — 共享契约：接口、DTO（record）、常量、Redis key。**并行开发的基础；被一切所依赖。改动它会波及所有地方——编辑前先广播。**
- `recsys-rec-engine` [app :8081] — 编排，主要的对外入口。`GET /api/recommend`；通过 `GET/POST /api/user/{id}/interests` 进行冷启动兴趣引导；`GET /api/search-ads`（搜索广告：`SearchAdsOrchestrator` 串 query 理解→广告召回→相关性门槛→预算过滤→复用排序模型出 pCTR/pCVR→校准→**oCPC 自动出价**→eCPM 竞价→GSP 计费→曝光埋点）+ `POST /api/ad/click`（CPC 点击计费）+ `POST /api/ad/conversion`（转化回传）。
- `recsys-gateway` [app :8080] — Spring Cloud Gateway 路由。
- `recsys-behavior` [app :8082] — 行为接入。`POST /api/behavior` → Kafka 或 DB。
- `recsys-web` [app :8090] — 演示前端。
- `recsys-offline` [app] — 离线任务：数据导入、CF 批处理、embedding 回填、样本生成。`train/` 中放有 Python 的 LightGBM/DeepFM/two-tower → ONNX 脚本。
- `recsys-streaming` [app] — Flink 实时特征任务（`RealtimeFeatureJob`），运行在本地嵌入式 MiniCluster 上（无需独立的 Flink 集群）。消费 Kafka `behavior-events` → 实时热门 ZSet `recall:rt_hot` + 每用户实时类目偏好 `rt:user:{id}`（Hash，field=category，value=近期计数，带 TTL）。`HotRecaller` 先读 `recall:rt_hot`，回退到离线的 `recall:hot`。`TagRecaller` 把 `rt:user:{id}` 融合进 TAG 通道——实时类目与静态的 `app_user.profile` 类目取并集，并按近期计数加权（`weight = 1 + boost·count/maxCount`），这样用户*当下*正在互动的内容即使其 profile 尚未包含也能在 TAG 中浮现（开关 `recsys.recall.tag.realtime-enabled`，默认开启；Redis 不可用时降级为仅静态）。构建 fat jar（`mvn -pl recsys-streaming -am package`）并通过 `bash recsys-streaming/run-streaming.sh` 运行（该脚本添加了 Flink 所需的 Java 21 `--add-opens` 标志）。需要 `docker compose --profile full up -d`（Kafka）以及以 `BEHAVIOR_USE_KAFKA=true` 运行的 behavior。
- `recsys-ad` [lib] — 搜索广告库(docs/05,M4–M6):`AdRecallService`(query→ad 关键词倒排/语义/兜底多路召回)、`RelevanceGate`(相关性门槛)、`OcpcBidder`(**oCPC 智能出价**:`bid=targetCpa·pCVR·k`,k 读 Redis `ad:ocpc:{adv}`,缺则退手动出价/k=1)、`BiddingService`(eCPM 竞价 + GSP 次价拍卖计费)、`IsotonicCalibrator`(pCTR 保序回归校准,读 Redis `ad:calib:{model}`)、`PacingService`(实时预算熔断 + pacing 平滑)、`AdEventLogger`(`ad_event` 曝光/点击/转化日志)。pCTR/pCVR **复用** `recsys-rank` 的排序模型(经 rec-engine 编排注入),广告只补"query / 钱 / 校准 / 拍卖 / 智能出价"几块净增量。每层优雅降级(关键词路兜底、SEMANTIC_AD 无向量则空、校准缺失退 identity、Redis 挂则不限预算、oCPC 缺参数/系数退 CPC)。
- `recsys-recall` / `recsys-rank` / `recsys-feature` / `recsys-embedding` / `recsys-content` / `recsys-user` [lib] — 召回、排序、特征、embedding、内容、用户画像库。`recsys-embedding` 有两套 `EmbeddingClient`：`GeminiEmbeddingClient`(默认,联网 REST)与 `LocalBgeEmbeddingClient`(`EMBEDDING_PROVIDER=local`,离线 ONNX——纯 Java `BgeTokenizer`(BERT WordPiece,读 vocab.txt,在线/离线契约同 `SparseFeatureEncoder`)+ onnxruntime 跑 bge-base-en-v1.5(768 维)+ CLS 池化 + L2)。模型由 `recsys-embedding/train/export_bge_onnx.py` 离线导出到文件系统(默认 `~/.recsys/models/...`,体积大不入 git,`BGE_MODEL_PATH`/`BGE_VOCAB_PATH` 可覆盖);缺文件则 `isReady()=false`、抛异常(query 理解 catch 后降级 null)。换 provider = 换向量空间,需全量重灌。`recsys-embedding` 另有生成式 `LlmClient`(契约在 `recsys-common.llm`):`GeminiChatClient`(`recsys.llm.enabled=true` + key 时装配,调 Gemini `generateContent`,Redis 缓存 `llm:cache:`),被 `recsys-query` 经 `ObjectProvider<LlmClient>` 可选注入做 LLM query 理解(拼写纠错/意图/改写,见 docs/04 §11);未就绪则 query 理解走纯词法兜底。

**单体优先，微服务就绪。** `RecEngineApplication` 使用 `@SpringBootApplication(scanBasePackages = "com.recsys")`，因此 `[lib]` 模块的 `@Component`/`@RestController` bean 全部被装配进一个进程。模块边界已经划好，后续拆分为独立服务的成本很低。跨模块耦合应限制在 `recsys-common` 接口上。

**请求流程**（`docs/02-架构设计.md` §6）：rec-engine 检查 `cache:rec:{userId}` → 冷启动检测 + 分层 A/B 分配 → 多通道召回（通道由实验的 recall 变体 / 冷启动覆盖控制）合并去重（主来源按优先级选取；**各路召回分先在 `MultiChannelRecallService` 内按路归一化到 [0,1] 再跨路取 max**——否则热度计数会压垮语义/向量路的余弦分）→ 排序（组装特征 + ONNX 或规则打分，策略按 rank 变体决定）→ 融合 recall+rank 分数，然后乘以逐通道加成（`recsys.fusion.channel-boost`，默认 `TAG: 1.5`，多命中取最大）以使诸如 TAG 这类兴趣信号——它携带实时 `rt:user` 类目偏好——不被 HOT/CF 热度淹没 → 重排（策略按 rerank 变体决定；冷启动强制强多样性）→ 截断、构建推荐理由、带桶标签记录曝光、缓存、返回。**搜索（query 驱动）场景**用 `recsys.search.*` 覆盖默认融合：抬高召回权重 / 压低排序权重、`channel-boost` 抬升 `SEMANTIC`+`LEXICAL`+`TAG`、`bypass-cold-start`，让 query↔item 相关性主导而非个性化热度（冷用户带 query 也走 query 主导链路）。**混合检索**：搜索请求由编排层注入 `recall-fusion=rrf`，`MultiChannelRecallService` 改用 RRF（`贡献=1/(k+rank)`，`recsys.recall.rrf-k`）按名次融合词法(LEXICAL/BM25)+向量(SEMANTIC)等多路（默认 userId 推荐仍走按路归一化取 max，不受影响）。

**在线与离线的拆分**是核心思想：在线链路是同步、毫秒级延迟的（只做“查表 + 轻量打分”）；离线/近线链路预计算繁重的工作（embedding、CF 倒排索引、训练好的模型）并将结果写入在线存储（pgvector、Redis）。

## 约定与契约（`recsys-common`）

- 核心接口：`RecallService`、`RankService`、`FeatureService`、`EmbeddingClient`。实现位于各自的 `[lib]` 模块中。
- DTO 与 channel/action 类型是 `dto/`、`recall/`、`rank/`、`constant/` 中的 Java `record` / 枚举。
- **所有 Redis key 都通过 `RedisKeys`**——不要把硬编码的 key 字符串散落在各模块中。
- **Embedding 维度固定为 768**（`recsys.embedding.dimension`，与 `item_embedding vector(768)` 匹配）。不同模型维度不同；混用会破坏检索。更换模型意味着对整个语料库重新做 embedding；通过 `model` 列追踪来源。
- 可调参数（召回配额、排序策略、重排上限、缓存 TTL）位于每个 `[app]` 的 `application.yml` 的 `recsys.*` 树下，可通过环境变量覆盖（如 `RANK_STRATEGY`、`EMBEDDING_PROVIDER`）；设计为可迁移到 Nacos 实现热更新。

## 并行开发模型

`PLAN.md` 将第一阶段拆分为相互独立的 Track（A：数据/embedding，B：召回，C：排序/特征，D：Python 训练，E：行为/离线，F：编排/网关/web），每个为一项独立任务。规则：

1. 只改动你所属 Track 拥有的模块。
2. 依赖 `recsys-common` 契约；不要单方面更改它们（先广播）。
3. 对跨 Track 的下游依赖打桩（mock），用 `// TODO` 标注真实来源，并在第二阶段集成时换入真实实现。

代码中不易看出的设计意图：**在线与离线的特征计算必须保持一致**（相同的特征名、相同的逻辑）。特征不一致 / 数据泄漏是部署后推荐质量崩溃的首要原因。每一层都设计为可优雅降级——hot 召回是始终在线的兜底，embedding 回退到本地 BGE/ONNX，排序在 ONNX 模型加载失败时回退到规则打分。
