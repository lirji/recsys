# 执行计划 PLAN —— 从 0 搭建推荐系统

> 本计划为**并行执行**设计。先做 Phase 0(顺序,打地基 + 定契约),之后 Phase 1 的多个 Track 可**并行**交给不同 Claude 任务,各自负责独立模块,互不冲突。
>
> 阅读顺序:先读 `docs/01-技术栈.md`、`docs/02-架构设计.md`(尤其第 5 节 API 契约)、`docs/03-关键技术点.md`。

---

## 里程碑(MVP 优先,逐步加深)

- **M1 跑通主链路**:能 `GET /api/recommend` 返回一批推荐(召回→规则排序→展示)。
- **M2 引入模型排序**:LightGBM 训练 → ONNX → Java 打分。
- **M3 反馈闭环 + 评估**:行为采集 → 重训 → AUC/CTR 指标。
- **M4 进阶(可选)**:多模态向量、Flink 实时特征、A/B 实验。

---

## Phase 0 —— 地基(必须顺序先做,单人/单任务完成)

> 这一步定义所有模块的边界和契约,**完成后并行才安全**。

- [ ] **0.1 仓库骨架**:建 Maven 多模块 `recsys`(见架构文档第 3 节),父 POM + 各空模块 + 统一依赖版本管理。
- [ ] **0.2 docker-compose**:postgres(pgvector 镜像 `pgvector/pgvector:pg16`)、redis;(可选)kafka、nacos。一条命令拉起。
- [ ] **0.3 数据库 schema**:执行架构文档第 4.1 节建表 + pgvector 扩展 + HNSW 索引(放 `recsys-offline/sql/`)。
- [ ] **0.4 公共契约 `recsys-common`**:把架构文档第 5 节的所有接口、record、DTO、Redis Key 常量落成 Java 代码。**这是并行的地基,最先定稿。**
- [ ] **0.5 配置规范**:`application.yml` 模板、`.env.example`(含 `GEMINI_API_KEY`)、`.gitignore`。
- [ ] **0.6 README**:如何启动(环境、compose、跑 demo)。

**Phase 0 完成判据**:`mvn clean install` 通过;`docker-compose up` 起得来;库表建好;`recsys-common` 接口可被各模块依赖。

---

## Phase 1 —— 并行开发(多 Track 同时进行)

> 每个 Track = 一个独立 Claude 任务。**只改自己 Track 的模块目录**,依赖 `recsys-common` 的接口,缺的下游用 mock/stub。完成后在 Phase 2 集成。

### Track A · 数据与向量化 〔依赖:0.3, 0.4〕
负责模块:`recsys-offline`(数据导入)、`recsys-embedding`、`recsys-content`
- [ ] A1 下载 MovieLens(`ml-latest-small`),写导入作业:movies → `item` 表。
- [ ] A2 实现 `EmbeddingClient` 接口:
  - `GeminiEmbeddingClient`(REST 调 Gemini,带 `emb:cache` 缓存、批量、限流重试)。
  - `LocalBgeEmbeddingClient`(ONNX,CPU 降级,可后做)。
- [ ] A3 灌向量作业:遍历 item → embedText → 写 `item_embedding`(记录维度/model)。
- [ ] A4 `recsys-content`:物品 CRUD + 查询 API。
- **产出/契约**:`EmbeddingClient` 可用;`item` / `item_embedding` 有数据。
- **验收**:能对任意文本返回固定维度向量;库里向量条数 = 物品数。

### Track B · 召回服务 〔依赖:0.4;运行期依赖 A 的数据〕
负责模块:`recsys-recall`
- [ ] B1 向量召回:pgvector 余弦 KNN 查询(架构文档 §3 SQL)。
- [ ] B2 i2i 召回:读 Redis `i2i:{itemId}`(倒排由 Track E 离线生成,先用空/mock)。
- [ ] B3 热门召回:读 `recall:hot` ZSet。
- [ ] B4 标签召回:按用户画像偏好类目查 item。
- [ ] B5 多路合并去重,实现 `RecallService.recall()`,带 channel 标记 + 配额配置。
- **验收**:给定 userId 返回合并后候选(各路可单测,数据缺失时热门兜底)。

### Track C · 排序与特征 〔依赖:0.4〕
负责模块:`recsys-rank`、`recsys-feature`
- [ ] C1 `recsys-feature`:用户/物品特征读写 Redis(`feat:user:*`/`feat:item:*`),提供离线写入工具。
- [ ] C2 排序 v1:加权规则打分实现 `RankService.rank()`(先不依赖模型,跑通链路)。
- [ ] C3 排序 v3 接口:集成 **ONNX Runtime**,加载 `model.onnx` 打分(模型文件由 Track D 离线产出,先用占位)。
- [ ] C4 特征装配:把 user/item/交叉特征组成模型输入向量,**离线在线逻辑一致**(关键!)。
- **验收**:给定 userId + 候选列表,返回带分数的有序结果;v1 无模型即可跑。

### Track D · 离线训练(Python)〔依赖:0.3〕
负责目录:`recsys-offline/train/`
- [ ] D1 样本构造:从 `user_behavior` 生成 `(label, features)`,负样本=曝光未点击,按时间切分训练/测试。
- [ ] D2 LightGBM 训练(CPU),输出 AUC/LogLoss。
- [ ] D3 导出 ONNX(`onnxmltools`),产出 `model.onnx` + 特征顺序说明(给 Track C 对齐)。
- **验收**:跑出一个 AUC 报告 + 一个可被 Java ONNX Runtime 加载的模型文件。

### Track E · 行为采集与离线作业 〔依赖:0.3, 0.4〕
负责模块:`recsys-behavior`、`recsys-offline`(CF 批算 / 热度 / 用户向量)
- [ ] E1 `recsys-behavior`:`POST /api/behavior` → 写 Kafka 或直接入库。
- [ ] E2 ItemCF/Swing 批算 → 写 Redis `i2i:{itemId}`(纯 Java)。
- [ ] E3 热度计算 → 写 `recall:hot`。
- [ ] E4 user_embedding 生成:聚合用户正反馈物品向量 → 写 `user_embedding`。
- **验收**:行为可上报;Redis 里有 i2i 倒排、热门、用户向量。

### Track F · 编排 / 网关 / 前端 〔依赖:0.4;集成期依赖 B/C〕
负责模块:`recsys-rec-engine`、`recsys-gateway`、`recsys-web`
- [ ] F1 `rec-engine`:编排 召回→排序→重排,实现 `GET /api/recommend`,带缓存 + 异常兜底。
- [ ] F2 重排:类目打散 + 业务规则(过滤已看/去重)+ 生成 reason。
- [ ] F3 `gateway`:路由配置。
- [ ] F4 `recsys-web`:演示页(输入 userId → 展示推荐卡片 + 召回来源 + 点击上报)。
- **验收**:打开网页,输入 userId,看到推荐结果与理由;点击能上报。

---

## 并行依赖关系图

```
Phase 0 (顺序) ──┬──> Track A (数据/向量化) ──┐
                 ├──> Track B (召回) ─────────┤
                 ├──> Track C (排序/特征) ─────┼──> Phase 2 集成
                 ├──> Track D (Python训练) ───┤
                 ├──> Track E (行为/离线作业) ─┤
                 └──> Track F (编排/网关/前端)─┘

运行期数据依赖(开发时用 mock 解耦):
  A 灌数据 → B 向量召回可用
  E 批算   → B 的 i2i/热门、C 的用户向量可用
  D 出模型 → C 的 v3 模型打分可用
```

> **并行安全要点**:① 各 Track 只动自己模块目录;② 共享类型只在 `recsys-common`(Phase 0 已定稿,改动需广播);③ 跨 Track 依赖一律先 mock,Phase 2 再换真实实现。

---

## Phase 2 —— 集成与联调

- [ ] 按真实链路串起来:数据灌好 → 离线作业跑完 → 启动各服务 → 前端验证。
- [ ] 用 v1 规则排序先全链路跑通(M1),再切 ONNX 模型(M2)。
- [ ] 端到端冒烟:新用户(冷启动走热门)+ 老用户(走向量/i2i)两条 case。

## Phase 3 —— 评估与打磨

- [ ] 离线评估:AUC、Recall@K、NDCG@K 脚本与报告。
- [ ] 在线模拟:点击行为回流 → CTR 对比(规则 vs 模型)。
- [ ] 文档收尾:架构图、原理讲解、踩坑记录(作品集亮点)。

## Phase 4 —— 进阶(可选)

- [ ] 多模态:电影海报 → Gemini 多模态向量。
- [ ] Flink 实时特征(复用你已有技能)。
- [ ] A/B 实验分桶 + 指标对比。

---

## 给"并行 Claude 任务"的下发模板

> 开一个任务时,这样描述即可让它独立工作:

```
背景:阅读 docs/01-技术栈.md、docs/02-架构设计.md(第5节契约)、docs/03-关键技术点.md。
任务:实现 PLAN.md 的 Track {X}。
约束:
  - 只修改 Track {X} 负责的模块目录,不动其他模块。
  - 依赖 recsys-common 已定义的接口/DTO,不要改它(需要改先提出来)。
  - 跨 Track 的下游依赖用 mock/stub,并在代码注释标 TODO 指明真实来源。
  - 每个子任务写最小单测;完成后对照该 Track 的"验收"自检。
产出:可编译通过的模块 + 简短的模块 README(如何运行/测试)。
```
