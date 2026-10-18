# 执行计划 PLAN —— 当前进度与原始建设记录

> **更新于 2026-10-18**，本次核对的代码基线为 `0f3500de`。原始 Track A–F、M1–M4 的功能已落地；当前根 POM 包含 **23 个子模块（含根 POM 共 24 个 reactor 项）**，前端为 `console/` + `recsys-console` BFF。本文件汇总当前进度并保留原始建设记录；专项实施状态以各专项文档为准，功能完成不等于全部效果、部署或生产验收完成。

## 当前进度（2026-10-18）

| 工作范围 | 已核实状态 | 证据与边界 |
| --- | --- | --- |
| 原始推荐、搜索、广告与离线能力 | Track A–F、M1–M4 功能完成；R9 多兴趣/图召回、A7 Uplift 已交付 | [原路线执行清单](docs/07-后续路线执行清单.md)；效果验收仍见下文 |
| 持久层与错误码治理 | MyBatis-Plus MP00–MP15、错误码 EC01–EC08 已实现并验证 | [持久层报告](docs/design/mybatis-plus-migration/PROJECT_REFACTORING_REPORT.md)、[错误码报告](docs/design/error-code-governance/PROJECT_REFACTORING_REPORT.md) |
| 企业可靠性 ER-S01–S14 | DONE；Sandbox CPM/CPC/CPA、自然曝光、混排、对账恢复与只读资金报表已闭环 | [实施状态](docs/design/enterprise-reliability/IMPLEMENTATION_SLICES.md)、[对账验收](docs/design/enterprise-reliability/ER-S13_RESULT.md)、[报表验收](docs/design/enterprise-reliability/ER-S14_RESULT.md)；不表示生产收费已启用 |
| 企业可靠性 ER-S15 | DONE；自然 Outbox、旧行为可靠事件、LIVE/HISTORY Seen 消费恢复均完成 | [S15 验收](docs/design/enterprise-reliability/ER-S15_RESULT.md)、[最终消费者验收](docs/design/enterprise-reliability/ER-S15C2_RESULT.md)；Flink 可靠窗口仍属 S19 |
| 企业可靠性 ER-S16 | DONE；单广告可靠写入、广告主状态扇出、跨 owner 批次与持久恢复完成 | A=`6fe79594`、B=`a25e511d`；[S16B 验收](docs/design/enterprise-reliability/ER-S16B_RESULT.md)，最终目录门禁 141 项通过，另有真实权限专项；消费者保障仍属 S17 |
| 桶对比演示与用户兴趣 | 本地验证通过，工具已合并推送 | `d340872a`、`5ec42263`、`0f3500de`；报表快照为 4 桶、875 曝光、120 点击、5 个不同用户；4 个新增用户已有差异化兴趣，真实推荐 TAG 候选各由 0 增至 50。均为演示验证，不能据此判断模型收益；[工具与使用说明](test-data/README.md) |
| 最近一次本机 Docker 部署 | `a25e511d` 本地验收通过 | 2026-10-17 部署 9 个应用，8 个后端健康、21 项 smoke、JAR/镜像与浏览器核验通过；后续三个提交仅更新演示工具，不表示已重新部署应用 |

**验证快照**：代码基线 `0f3500de` 的 [CI 39007894820](https://github.com/lirji/recsys/actions/runs/39007894820) 已完成，9/9 job 成功。此前演示工具提交出现过目录故障恢复耗时断言和浏览器宿主启动失败；最新成功只代表本次精确提交的运行结果，不将旧失败改写为通过。

本机演示与部署证据分别保存在 `.dev/test-data/ab-board-v2/`、`.dev/test-data/user-interests-v1/`、`.dev/deployments/latest-a25e511d06e3/` 下的 `PROGRESS.md` 和结果文件中；这些本机材料未纳入 Git，需保留以便恢复和精确清理。兴趣任务另产生 40 条推荐曝光，未重算上述桶对比报表，875/120 是已发布报表快照。

### 后续实施范围

企业可靠性沿用[原计划](docs/design/enterprise-reliability/PLAN.md)与[唯一实施状态](docs/design/enterprise-reliability/IMPLEMENTATION_SLICES.md)，不重编号、不在本次文档更新中启动开发：

| 切片 | 剩余工作 | 状态与前置条件 |
| --- | --- | --- |
| ER-S17 | 版本化目录投影、Inbox、墓碑与 Redis 可靠刷新 | DONE / 本地PASS；C17冻结，真实组件及回归32项通过；[验收](docs/design/enterprise-reliability/ER-S17_RESULT.md) |
| ER-S18 | 日志补洞、持久检查点与并发领取 | TODO；依赖 S06，正式契约待冻结；覆盖低 ID 晚提交与恢复 |
| ER-S19 | Flink checkpoint、事件时间/迟到与 Redis 幂等窗口投影 | TODO；依赖 S15，正式契约待冻结；当前 MiniCluster 不代表本项完成 |
| ER-S20 | 主召回总 deadline、有界执行与缓存故障回源 | TODO；依赖 ER-D0，正式契约与负载资源预算待冻结 |
| ER-S21 | 非关键 shadow 有界过载丢弃 | TODO；依赖 S20，需验证主链路资源隔离与关键事件不丢弃 |
| ER-S22–S25 | 保持原计划范围 | 未实施、本轮暂停，等待后续授权 |

### 当前下一步

1. 2026-10-18 用户指定先完成 ER-S17、ER-S18；按顺序实现、验证并交付，不启动 S19～S25。
2. 效果与全链路验收仍保留三项：可比较的 v1/ONNX 在线 CTR 实验、完整 Gateway/OIDC 自动化 E2E、A6 DFM 真实数据在线闭环；已完成的演示与浏览器专项不重复建设。
3. R9（MIND + LightGCN）与 A7（Uplift 因果采样/增量竞价）的功能交付保持完成；真实流量效果按[路线清单的质量跟进](docs/07-后续路线执行清单.md#不阻塞功能完成的质量跟进)继续管理。

---

## 原始建设范围与历史验收

以下保留 2026-06-01 的 Phase/Track 划分及后续功能回填。历史模块名、实现名、环境与数据规模只描述当时验收，不作为当前部署参数或新的任务授权；当前持久层组织以 MyBatis-Plus 专项报告为准。

原计划先做 Phase 0（地基与契约），再并行实施 Phase 1 的独立 Track，最后集成。历史阅读入口：[技术栈](docs/01-技术栈.md)、[架构设计](docs/02-架构设计.md)、[关键技术点](docs/03-关键技术点.md)。

---

## 里程碑(MVP 优先,逐步加深)

- ✅ **M1 跑通主链路**:能 `GET /api/recommend` 返回一批推荐(召回→规则排序→展示)。**已完成**
- ✅ **M2 引入模型排序**:LightGBM 训练 → ONNX → Java 打分。**已完成**
- ✅ **M3 反馈闭环 + 评估**:行为采集 → 曝光回流/重训样本 → AUC/CTR/@K 指标。**能力已完成；有效的 v1 vs ONNX 在线 CTR 对比结果待补**
- ✅ **M4 进阶(可选)**:多模态向量、Flink 实时特征、A/B 实验。**已完成（Flink 为本地 MiniCluster 教学态）**

---

## Phase 0 —— 地基(必须顺序先做,单人/单任务完成)

> 这一步定义所有模块的边界和契约,**完成后并行才安全**。

- [x] **0.1 仓库骨架**:Maven 多模块 `recsys`,父 POM + 12 个模块 + 统一版本管理。**注:JDK 用 21(本机无 17);`maven-compiler-plugin` 开 `-parameters` 供 Jackson 反序列化 record。**
- [x] **0.2 docker-compose**:postgres(`pgvector/pgvector:pg16`)、redis(默认);kafka、nacos 走 `--profile full`。
- [x] **0.3 数据库 schema**:`recsys-offline/sql/01_schema.sql`,容器首启自动执行(挂载 `docker-entrypoint-initdb.d`)。
- [x] **0.4 公共契约 `recsys-common`**:接口/record/DTO/RedisKeys 全部落地(14 个类)。**并行地基,已定稿。**
- [x] **0.5 配置规范**:各模块 `application.yml`、`.env.example`、`.gitignore`(`.env` 不进 git)。
- [x] **0.6 README + CLAUDE.md**:启动说明 + 项目指南。

**Phase 0 完成判据**:✅ `mvn clean install` 通过;✅ `docker compose up` 起得来;✅ 库表建好(5 表 + pgvector + HNSW);✅ `recsys-common` 被各模块依赖。

---

## Phase 1 —— 并行开发(多 Track 同时进行)

> 每个 Track = 一个独立 Claude 任务。**只改自己 Track 的模块目录**,依赖 `recsys-common` 的接口,缺的下游用 mock/stub。完成后在 Phase 2 集成。

### Track A · 数据与向量化 ✅ 已完成 〔依赖:0.3, 0.4〕
负责模块:`recsys-offline`(数据导入)、`recsys-embedding`、`recsys-content`
- [x] A1 MovieLens(`ml-latest-small`)导入作业 `import-items`:**9742 部电影 → `item` 表**(自动下载解压;popularity=评分数)。
- [x] A2 `EmbeddingClient` 实现:
  - [x] `GeminiEmbeddingClient`:REST 调 `gemini-embedding-001`,`emb:cache` 缓存、重试退避、**L2 归一化**、429 配额优雅停止。
  - [x] `LocalBgeEmbeddingClient`:本地 BGE-base-en-v1.5 ONNX CPU 推理，Java WordPiece 分词 + 池化 + L2 归一化；`provider=local` 时启用，缺模型会标记未就绪并由在线调用方降级。
- [x] A3 灌向量作业 `backfill-embedding`:遍历 item → embedText → 写 `item_embedding`(含 model);支持 `--skip-existing` 续跑。
- [x] A4 `recsys-content`:`JdbcContentService` 物品 CRUD + 批量查询。
- **验收**:✅ 文本→768 维归一化向量；✅ 已切本地 BGE 全量重灌 **9742/9742 item + 610 user + 800 ad**，统一 768 维向量空间（2026-06-18）。

### Track B · 召回服务 ✅ 已完成 〔依赖:0.4;运行期依赖 A/E 数据〕
负责模块:`recsys-recall`
- [x] B1 向量召回 `VectorRecaller`:取 `user_embedding` → pgvector 余弦 KNN。**实测语义正确**。
- [x] B2 i2i 召回 `I2iRecaller`:读 Redis `i2i:{itemId}`；倒排已由 Track E 的 ItemCF 作业生成并验证。
- [x] B3 热门召回 `HotRecaller`:读 `recall:hot` ZSet;**Redis 空时降级查库 popularity,兜底永不空手**。
- [x] B4 标签召回 `TagRecaller`:读 `app_user.profile` JSONB 偏好类目查 item(无画像返回空)。
- [x] B5 `MultiChannelRecallService` 合并去重,带 channel 标记 + 配额配置;任一路失败降级。
- **验收**:✅ 多路融合实测(VECTOR+HOT 混合);数据缺失时热门兜底。

### Track C · 排序与特征 ✅ 已完成(v1)〔依赖:0.4〕
负责模块:`recsys-rank`、`recsys-feature`
- [x] C1 `RedisFeatureService`:特征读写 Redis(`feat:user:*`/`feat:item:*`)+ 离线写入方法。
- [x] C2 排序 v1 `RuleRankService`:加权规则打分,特征快照随结果返回。
- [x] C3 排序 v3:`OnnxRankService`(onnxruntime 加载 `model.onnx`,共享 FeatureAssembler 装配→批量打分,加载失败回退规则);`RankRouter` @Primary 按 `recsys.rank.strategy` 路由。实测 `RANK_STRATEGY=onnx` 模型加载成功、全程打分无回退。
- [x] C4 特征装配:抽出共享 `FeatureAssembler`(固定 FEATURE_ORDER,纯函数),在线 OnnxRankService/RuleRankService 与离线 gen-samples 共用,杜绝特征穿越;离线 `build-features` 物化 feat:* 为同源。
- **验收**:✅ 给定候选返回有序结果;v1/onnx 均可跑,模型缺失自动回退规则。

### Track D · 离线训练(Python)✅ 已完成(2026-06-01)〔依赖:0.3, 运行期依赖 E 的行为数据〕
负责目录:`recsys-offline/train/`
- [x] D1 样本构造:Java 作业 `gen-samples`,正样本=RATING≥4,负样本=按热度采样的未评分物品(MovieLens 无曝光日志,采样近似曝光未点击),按 ts 时间切分 train/valid,经共享 FeatureAssembler 装配 → `train/samples.csv`(实测 14.5 万行)。
- [x] D2 LightGBM 训练(CPU,`train/train_lgbm.py`),**AUC=0.6300 / LogLoss=0.6336**。
- [x] D3 导出 ONNX(onnxmltools, zipmap=False 出概率张量)→ `recsys-rank/src/main/resources/model/model.onnx` + `feature_order.json`,onnxruntime 回读校验通过。
- **验收**:✅ AUC 报告 + Java onnxruntime 成功加载并打分(高/低匹配样本 CTR 可区分)。
- 前置 Java 作业:`build-features`(物化 feat:* 到 Redis)→ `gen-samples`(造样本);训练详见 `train/README.md`。
- 已知简化:负样本为采样近似;特征用全量历史含轻度数据穿越(离线 AUC 偏乐观),生产应按事件时间 as-of 取特征。

### Track E · 行为采集与离线作业 ✅ 已完成(2026-06-01)〔依赖:0.3, 0.4〕
负责模块:`recsys-behavior`、`recsys-offline`(CF 批算 / 热度 / 用户向量)
- [x] E1 `recsys-behavior`:`POST /api/behavior`(+ `/batch`)→ `user_behavior`，推荐响应/行为事件贯通 `exposureId`，同曝光 CLICK 由 PostgreSQL 部分唯一索引去重；action 一律大写入库。旧路径按 `use-kafka` 可选发布；S15 已补充同库 Outbox 与消费者恢复，受独立开关控制。无幂等键的普通 POST 不承诺全局幂等，详见 [S15 验收](docs/design/enterprise-reliability/ER-S15_RESULT.md)。
- [x] **bootstrap** `import-behavior` 作业:ratings.csv(10 万条)→ `user_behavior`(action=RATING, value=评分, scene=ml-import, 幂等)。冷启动喂数,线上真实上报后续进同表。
- [x] E2 `item-cf` 作业:`ItemCfJob` 经典 ItemCF + IUF 活跃用户惩罚 + 热门物品惩罚,每物品 TopK 写 Redis `i2i:{itemId}`(管道批量)。实测 5959 物品出倒排,Toy Story→Toy Story 2/狮子王/阿拉丁,质量正确。
- [x] E3 `hot` 作业:`HotJob` 从 `user_behavior` SQL 加权聚合(CLICK1/LIKE2/PLAY1/RATING=value)→ `recall:hot` ZSet。历史实测写 1000 条，HotRecaller 走 Redis；当前仍为删除后写入两条命令，存在中间窗口，不宣称原子替换，见[持久层报告的剩余风险](docs/design/mybatis-plus-migration/PROJECT_REFACTORING_REPORT.md#remainingrisks--technicaldebt)。
- [x] E4 `user-embedding` 作业:`UserEmbeddingJob` 聚合用户正反馈物品向量(评分加权)→ L2 归一化 → `user_embedding`(默认整表重建)。实测覆盖 598 用户。
- **验收**:✅ 行为可上报落库(单条+批量实测);Redis 有 i2i 倒排(5959)、热门(1000)、用户向量(598);真实用户 `GET /api/recommend?userId=1` 由 **VECTOR+I2I 真实召回**驱动(2571=黑客帝国来自 ItemCF),不再靠手造向量/热门兜底。
- **原已知限制（已解决）**:~~item_embedding 仅 1000 条，user_embedding 覆盖受 Gemini 配额限制~~ → 已切本地 BGE 全量重灌；数据质量报告记录 item embedding 覆盖率 100%、user embedding 覆盖率 99.35%。

### Track F · 编排 / 网关 / 前端 ✅ 已完成〔依赖:0.4;集成期依赖 B/C〕
原负责模块:`recsys-rec-engine`、`recsys-gateway`、`recsys-web`（后者现已演进为 `console/` + `recsys-console`）
- [x] F1 `rec-engine`:`RecommendOrchestrator` 编排 召回→排序→重排,`GET /api/recommend`,`RecCache` 缓存 + 异常兜底。**含召回分/排序分融合**(M1 特征稀疏期保证排序有意义)。
- [x] F2 重排:类目打散(`maxSameCategory`)+ 生成 reason(按召回 channel)；已接入已看过滤与多路去重。
- [x] F3 `gateway`:统一入口已完成；推荐/搜索/广告/feed/query/用户/实验 → rec-engine，行为 → behavior，广告主管理 → advertiser，控制台 BFF → recsys-console；支持 Nacos `lb://` 与无 Nacos 的 static 路由。
- [x] F4 `recsys-web`:原 Thymeleaf 演示页已完成，后续演进为 React `console/` + `recsys-console` BFF，覆盖推荐、搜索、广告、实验与离线报表。
- **验收**:✅ `GET /api/recommend` 实测返回结果与理由;前端页面就绪。

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

- [x] 按真实链路串起来:数据灌好 → 离线作业跑完 → 启动各服务 → 前端验证。
- [x] 用 v1 规则排序先全链路跑通(M1),再切 ONNX 模型(M2)。
- [x] 端到端冒烟:新用户(冷启动走热门)+ 老用户(走向量/i2i)两条 case。**两条路径已有历史手工实测；后续已增加自然曝光、广告、混排、CPC/CPA 与对账的浏览器专项并纳入 CI。完整 Gateway/OIDC、多进程及 PostgreSQL/Redis 联合断言的自动化 E2E 仍待补，不能把专项宿主等同于完整部署链路。**

## Phase 3 —— 评估与打磨

- [x] 离线评估:AUC、Precision/Recall/NDCG/MAP/MRR/HitRate/Coverage/Diversity/Novelty @K 脚本与报告；另有严格无泄漏 eval。
- [ ] 🟡 在线效果验收:点击回流、分层 A/B、CTR 报表与显著性检验能力已实现；**2026-10-17 的 DEMO 报表已有 120 条点击，数据/API/浏览器链路验证通过，但合成点击与跨场景汇总不构成可比较的 v1/ONNX 效果结论**。
- [x] 文档收尾:项目总览、架构与能力说明、踩坑记录，以及 `docs/skills/` 19 篇技术专题。

## Phase 4 —— 进阶(可选)

- [x] 多模态:电影海报 → Gemini 图像向量，与文本向量加权融合后回写 `item_embedding`；需自行准备 TMDB 海报数据与 API 配置。
- [x] Flink 实时特征:Kafka 行为流 → 实时热门、实时类目偏好与实时序列；当前为本地 MiniCluster 教学态。
- [x] A/B 实验分桶 + 指标对比:recall × rank × rerank（另含广告层）确定性分桶、曝光归因、CTR/置信区间/显著性报表与控制台均已落地。

### 尚未完全闭环的验收项（2026-10-18 核对）

- [x] 演示点击 → 真实行为接口 → 离线 A/B 报表 → BFF/浏览器展示：已有非零点击的 DEMO 数据与幂等验证，见[演示工具](test-data/README.md)；仅关闭演示链路验收。
- [ ] 用分组与统计口径可比较的 `v1` vs `onnx` 流量产出 CTR、lift、置信区间与显著性结果；明确合成/真实样本、独立用户数量、实验窗口及跨场景数据边界。现有 DEMO 不关闭此项。
- [x] 自然曝光、广告与混排等专项浏览器验收已落地并纳入[CI](.github/workflows/ci.yml)；证据见 [S09](docs/design/enterprise-reliability/ER-S09_RESULT.md)、[S10](docs/design/enterprise-reliability/ER-S10_RESULT.md)、[S14](docs/design/enterprise-reliability/ER-S14_RESULT.md)。
- [ ] 完整覆盖“浏览器/console → Gateway/OIDC → 独立 rec-engine/behavior → PostgreSQL/Redis”的自动化 E2E。现有专项使用隔离宿主或测试登录适配器，仍缺同一次完整部署链路验收。
- [ ] 完成 A6 DFM 的真实数据闭环验收：复核并关闭转化 `ts >= click.ts` 的样本关联、训练数据质量硬门禁、持久化广告分片 schema 等历史阻塞，再以足量真实点击/转化跑通“事件 → 样本 → 训练 → 新 ONNX → 在线 pCVR 消费”。[2026-09-01 QA 报告](docs/qa/a5-r7-a6-full-flow-0901-1049/QA_REPORT.md)仍是最近定位到的完整 A6 验收证据；本次未复测库状态，不将其中“仅 2 次点击/缺字段”当作当前环境事实，也未发现可关闭本项的新证据。

---

## 原始并行任务下发模板（历史参考）

> 以下保留原始模板，不用于重新启动已经完成的 Track。新任务按现有专项计划、契约和项目规则执行。

```
背景:阅读 docs/01-技术栈.md、docs/02-架构设计.md(第5节契约)、docs/03-关键技术点.md。
任务:实现 PLAN.md 的 Track {X}。
约束:
  - 只修改 Track {X} 负责的模块目录,不动其他模块。
  - 依赖 recsys-common 已定义的接口/DTO,不要改它(需要改先提出来)。
  - 跨 Track 的下游依赖用 mock/stub,并在代码注释标 TODO 指明真实来源。
  - 每个子任务写最小单测;完成后对照该 Track 的"验收"自检。
  - 构建用 JDK 21 + 本机 Maven:
      export JAVA_HOME=$(/usr/libexec/java_home -v 21)
      export PATH="/Users/liruijun/personal/devUtils/apache-maven-3.9.12/bin:$PATH"
产出:可编译通过的模块 + 简短的模块 README(如何运行/测试)。
```

---

## M1 实施纪要(2026-06-01)

### 实际怎么落地的
- **并行 subagent 失败**:同时派 A/B/C+F 三个后台 agent,但它们与主会话**共享账户会话额度**,一启动即触顶、**零产出**(只生成了 CLAUDE.md)。→ 改由主会话**串行手写全部代码**并逐一实测。
- **经验**:额度紧张时优先主会话串行;并行派 agent 前先确认额度充足。

### 已实测验证(非"应该能跑")
| 验证项 | 结果 |
|---|---|
| MovieLens 导入 | ✅ 9742 部电影,popularity 正确 |
| Gemini 向量化 | ✅ 768 维,L2 归一化(模长=1.0000) |
| 向量语义检索 | ✅ Toy Story → Jumanji/Balto/Babe(同期儿童片) |
| `GET /api/recommend` | ✅ 召回→排序→重排→理由 全链路 |
| 多路融合 | ✅ VECTOR+HOT 混合排序 |
| 冷启动 | ✅ 新用户无向量 → 纯热门兜底 |

### 关键坑(写给未来的自己)
1. **Gemini 免费层 embedding 每天每模型限 1000 次** → 9742 部只灌了 1000 条。次日续跑:
   `mvn -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments="--job=backfill-embedding --skip-existing"`
2. **key 只有 `gemini-embedding-001` 可用**(`text-embedding-004` 报 404);默认 3072 维,用 `outputDimensionality=768` 降维,**降维后必须 L2 归一化**否则余弦失准。
3. **本机无 JDK 17**,用 JDK 21;Maven 在 `/Users/liruijun/personal/devUtils/apache-maven-3.9.12`(不在 PATH)。
4. **record DTO 经 Redis 缓存 JSON 往返**需 `-parameters` 编译参数(已在父 POM 配 maven-compiler-plugin)。

### M1 已知简化（后续均已解决）
- ~~`RecommendItem.recallFrom` 只存主来源~~ → 已透传同一物品命中的全部召回路，主路排首位。
- ~~i2i / tag 缺离线数据、依赖热门兜底~~ → ItemCF、画像与实时类目偏好均已接通。
- ~~未过滤已看 / 去重~~ → `SeenFilter` 与多路合并去重已接入，并有集成测试覆盖。
- ~~真实用户无 `user_embedding`~~ → 已全量重建，数据质量报告记录覆盖率 99.35%。
