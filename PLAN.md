# 执行计划 PLAN —— 从 0 搭建推荐系统

> 本计划为**并行执行**设计。先做 Phase 0(顺序,打地基 + 定契约),之后 Phase 1 的多个 Track 可**并行**交给不同 Claude 任务,各自负责独立模块,互不冲突。
>
> 阅读顺序:先读 `docs/01-技术栈.md`、`docs/02-架构设计.md`(尤其第 5 节 API 契约)、`docs/03-关键技术点.md`。

> **当前状态(2026-06-01)**:✅ Phase 0 完成;✅ **M1 主链路已打通并实测**(`GET /api/recommend` 跑通,向量召回语义正确、冷启动正确)。
> Track A/B/C/F 已完成,Track D/E 未开始。详见文末「M1 实施纪要」。

---

## 里程碑(MVP 优先,逐步加深)

- ✅ **M1 跑通主链路**:能 `GET /api/recommend` 返回一批推荐(召回→规则排序→展示)。**已完成**
- ⬜ **M2 引入模型排序**:LightGBM 训练 → ONNX → Java 打分。
- ⬜ **M3 反馈闭环 + 评估**:行为采集 → 重训 → AUC/CTR 指标。
- ⬜ **M4 进阶(可选)**:多模态向量、Flink 实时特征、A/B 实验。

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
  - [ ] `LocalBgeEmbeddingClient`:留 stub(`provider=local` 时启用),ONNX 接入待做。
- [x] A3 灌向量作业 `backfill-embedding`:遍历 item → embedText → 写 `item_embedding`(含 model);支持 `--skip-existing` 续跑。
- [x] A4 `recsys-content`:`JdbcContentService` 物品 CRUD + 批量查询。
- **验收**:✅ 文本→768 维归一化向量;⚠️ **向量只灌了 1000/9742**(Gemini 免费层每天每模型 1000 次上限),功能已验证,余量等配额续跑。

### Track B · 召回服务 ✅ 已完成 〔依赖:0.4;运行期依赖 A/E 数据〕
负责模块:`recsys-recall`
- [x] B1 向量召回 `VectorRecaller`:取 `user_embedding` → pgvector 余弦 KNN。**实测语义正确**。
- [x] B2 i2i 召回 `I2iRecaller`:读 Redis `i2i:{itemId}`(倒排待 Track E 生成,当前返回空)。
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
- [x] E1 `recsys-behavior`:`POST /api/behavior`(+ `/batch`)→ `BehaviorService` 落库 `user_behavior`(`use-kafka=true` 时投 Kafka,不可用自动降级入库)。action 一律大写入库,对齐 I2iRecaller 查询口径。
- [x] **bootstrap** `import-behavior` 作业:ratings.csv(10 万条)→ `user_behavior`(action=RATING, value=评分, scene=ml-import, 幂等)。冷启动喂数,线上真实上报后续进同表。
- [x] E2 `item-cf` 作业:`ItemCfJob` 经典 ItemCF + IUF 活跃用户惩罚 + 热门物品惩罚,每物品 TopK 写 Redis `i2i:{itemId}`(管道批量)。实测 5959 物品出倒排,Toy Story→Toy Story 2/狮子王/阿拉丁,质量正确。
- [x] E3 `hot` 作业:`HotJob` 从 `user_behavior` SQL 加权聚合(CLICK1/LIKE2/PLAY1/RATING=value)→ `recall:hot` ZSet(原子替换)。实测写 1000 条,HotRecaller 走 Redis。
- [x] E4 `user-embedding` 作业:`UserEmbeddingJob` 聚合用户正反馈物品向量(评分加权)→ L2 归一化 → `user_embedding`(默认整表重建)。实测覆盖 598 用户。
- **验收**:✅ 行为可上报落库(单条+批量实测);Redis 有 i2i 倒排(5959)、热门(1000)、用户向量(598);真实用户 `GET /api/recommend?userId=1` 由 **VECTOR+I2I 真实召回**驱动(2571=黑客帝国来自 ItemCF),不再靠手造向量/热门兜底。
- **已知限制**:item_embedding 仅 1000 条(Gemini 配额),user_embedding 只覆盖正反馈落在这 1000 物品内的用户;灌满向量后重跑 user-embedding 即扩大覆盖。

### Track F · 编排 / 网关 / 前端 ✅ 已完成(网关待补)〔依赖:0.4;集成期依赖 B/C〕
负责模块:`recsys-rec-engine`、`recsys-gateway`、`recsys-web`
- [x] F1 `rec-engine`:`RecommendOrchestrator` 编排 召回→排序→重排,`GET /api/recommend`,`RecCache` 缓存 + 异常兜底。**含召回分/排序分融合**(M1 特征稀疏期保证排序有意义)。
- [x] F2 重排:类目打散(`maxSameCategory`)+ 生成 reason(按召回 channel)。〔过滤已看/去重待 Track E 行为数据〕
- [ ] F3 `gateway`:骨架在,路由已配,未联调(单体起步直接打 rec-engine :8081)。
- [x] F4 `recsys-web`:Thymeleaf 演示页(输入 userId → 推荐卡片 + 召回来源 + 点击上报)。
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

### M1 已知简化(后续可优化)
- `RecommendItem.recallFrom` 目前只存**主来源**(多路命中未合并全部 channel)。
- i2i / tag 召回因 Track E 离线数据未生成,当前返回空,靠热门兜底。
- 过滤"已看过 / 去重"待 Track E 行为数据落地后接入。
- 真实用户的 `user_embedding` 待 Track E E4 生成(M1 用手造向量验证了向量召回)。

### 下一步建议顺序
**Track E**(行为采集 + ItemCF 批算 i2i + 热门 ZSet + user_embedding 聚合)→ 让四路召回全部有真实数据 → 再 **Track D**(LightGBM→ONNX,进入 M2 模型排序)。
