# 排序 · CTR 与多任务模型全家桶

> **解决什么**:召回给的几百候选,精准排出"用户最可能点击/转化"的顺序。这是推荐系统**最核心、最能体现技术含量**的一层。
> 本项目实现 **9 个排序策略 + 双塔粗排**(`recsys-rank`),由 `recsys.rank.strategy`(`RANK_STRATEGY`)切换,**任一模型缺失/失败一律回退规则打分**。

## 1. 演进主线:从规则到序列到多任务

```
v1 规则  →  LightGBM(GBDT)  →  DeepFM(FM+DNN)  →  DCN v2(显式高阶交叉)
                                     │
                                     ├─► MMoE / PLE(多任务 + ESMM)
                                     └─► DIN → DIEN → SIM(行为序列 → 兴趣演化 → 长序列)
```
每一步都是对上一步的**净增量**,不是替换。评估时用 `eval` 任务在同一 ground-truth 上公平对比(`run_eval_compare.sh`)。

## 2. 九策略一览

`RankRouter`(`@Primary`,编排器注入的 `RankService`)按 strategy 分发,支持分层 A/B 的 per-request override,并埋点 `recsys.rank{requested,served,reason}`。

| strategy | 类 | 模型文件 | 输入 | 输出 | 核心增量 |
|---|---|---|---|---|---|
| `v1`/rule | `RuleRankService` | 无 | dense(5) | score | `w_pop·pop + w_profile·aff`,零模型兜底 |
| `onnx` | `OnnxRankService` | `model.onnx` | `dense[N,5]` | CTR | LightGBM GBDT,业界主流 |
| `deepfm` | `DeepFmRankService` | `model_deepfm.onnx`+schema+vocab | `dense[N,d]`+`sparse[N,3\|6]` | `ctr` | FM 二阶交叉 + DNN + id embedding |
| `dcn` | `DcnRankService` | `model_dcn.onnx`+schema | dense+sparse | `ctr` | **Cross Network v2** 显式可控阶数高阶交叉 ∥ Deep |
| `mmoe` | `MmoeRankService` | `model_mmoe.onnx`+schema | dense+sparse | **`ctr`+`cvr`** | 多任务(多专家+门控)+ ESMM |
| `ple` | `PleRankService` | `model_ple.onnx`+schema | dense+sparse | **`ctr`+`cvr`** | **PLE/CGC** 分离共享/专属专家,解决 MMoE 跷跷板 |
| `din` | `DinRankService` | `model_din.onnx`+schema | dense+sparse+**seq[N,L]**+seq_len | `ctr`+`cvr` | 候选对历史做 target-attention |
| `dien` | `DienRankService` | `model_dien.onnx`+schema | 4-input(同 DIN) | `ctr`+`cvr` | 兴趣抽取(GRU)+ 兴趣演化(AUGRU) |
| `sim` | `SimRankService` | `model_sim.onnx`+schema | 4-input(逐候选 GSU 子序列) | `ctr`+`cvr` | GSU 类目硬检索 + ESU,长序列(500) |
| 粗排 | `PreRankService`(常驻) | `TowerScorer`(可选) | recallScore+pop+aff(+tower) | 截断列表 | 精排前轻量过滤,可叠双塔学习分 |

> **PLE 是本项目新增的第 9 个策略**(CLAUDE.md 尚未收录):`PleRankService` + `train_ple.py`,是 MMoE 的镜像,把专家分成"共享专家 + 每任务专属专家",缓解多任务负迁移(跷跷板效应)。

## 3. 三大在线/离线契约(重中之重)

模型的在线打分和离线训练**必须逐位一致**,否则线上崩。三个编码器就是契约的载体:

### 3.1 `FeatureAssembler` —— 稠密特征
- **基础 5 维**(`FEATURE_ORDER`):`item_pop_norm, item_avg_rating, user_act_norm, user_avg_rating, user_cat_affinity`。
- **扩展 8 维**(`EXTENDED_ORDER`,S2 新增):再加 `user_cat_cnt_norm, user_cat_ratio, item_rating_std`。
- 深模型按 schema `dense_order` 选 5 或 8(缺则默认 5)。交叉特征读 `catavg:<cat>`,缺失回退用户均分;冷用户/冷物品用中性默认(3.5/5)保活。

### 3.2 `SparseFeatureEncoder` —— 稀疏特征(id embedding)
```
[floorMod(userId, userBuckets), floorMod(itemId, itemBuckets), categoryVocab[cat] || 0]
```
- buckets 与 vocab 来自训练产出的 `rank_schema.json` / `category_vocab.json`(打进 classpath)。
- **分桶取模 + 词表必须与训练器完全一致**——这是 id embedding 的在线/离线契约,类比稠密特征的 `FeatureAssembler`。
- **可选 semantic-id**:schema `semantic_id=true` 时追加 RQ-VAE `(c0,c1,c2)`,`width()` 从 3 变 6(DeepFM/DCN 的 `--semantic-id`)。

### 3.3 `SequenceEncoder` —— 行为序列(DIN/DIEN/SIM)
- 固定 `seq_len`,右侧 padding(oldest→newest),`bucket=floorMod(itemId,itemBuckets)`。
- 返回 `(seq, len)`,padding 位置由 `seq_len` 掩码(空序列 → pooled 向量强制 0,冷用户不出垃圾值)。
- **在线序列来源**(R2):Redis `rt:user:seq:{id}`(实时序列)→ DB 回退 + cache-aside(TTL 3600)。离线由 `gen-samples-mt` 输出 as-of 序列(当前事件前快照,无泄漏)。

契约由 golden test 守护:`RankEncoderContractGoldenTest`、`SparseFeatureEncoderTest`、`SequenceEncoderTest`。

## 4. 多任务与 ESMM(mmoe/ple/din/dien/sim 共用)

### 融合两个 head
```
score = pCTR · (cvrBias + cvrWeight · pCVR)
```
权重在 `recsys.rank.multi-task`(默认 `cvrBias=0.1, cvrWeight=1.0`),**无需重训即可调**;`cvrBias=0,cvrWeight=1` ⇒ pCTCVR。`tune-fusion` 作业可离线网格搜索最优权重。

### ESMM 消除 CVR 样本选择偏差
样本文件 `samples_mt.csv`(`gen-samples-mt`)带**两个标签**:`label_click`(是否任何交互)、`label_like`(评分≥4)。ESMM 损失在**全曝光空间**上算:
```
Loss = BCE(pCTR, click) + BCE(pCTR·pCVR, like)
```
CVR 塔从不直接看"曝光未点击",从而消除 CVR 的样本选择偏差(传统 CVR 只在点击样本上训,有偏)。

## 5. PAL 位置偏差去偏(mmoe/ple/din)

**问题**:训练样本里高位置天然点击多,模型会把"位置高"误学成"物品好"。
**PAL 方案**:训练时 CTR head 叠加一个**位置偏置塔** `b(position)`,吃 `gen-samples-mt` 的 `position` 列;**导出 ONNX 时 `position=None`,该塔不入图**。
- **相对"位置当特征"的关键优势**:在线输入/编码器/契约一概不变(PAL 相对"位置当特征 + 预测时置 0"零在线改动)。
- `position=0` = 位次未知(评分派生样本无真实曝光位次 → 默认全 0,PAL 休眠、精度等同未去偏)。
- **真实位次走曝光日志闭环**:`ExposureLogger` 记 `user_behavior.position`,`gen-samples-impr` 从曝光日志造样本(真负样本 + 真位次)→ PAL 激活(实测 relevance head AUC 0.44→0.56)。

## 6. 双塔粗排(R6)

`PreRankService` 在精排前把候选从几百截到 50(`pre-rank.limit`)。`pre-rank.mode=two-tower` 时,经 `TowerScorer`(由 `TwoTowerRecaller` 实现,复用 user 塔 + `item_tower_embedding` 打候选双塔点积)把归一化学习分叠加进线性粗排分:
```
score = recallW·rNorm + popW·pop + affW·aff + towerW·tower
```
缺向量/未就绪 → 退纯线性;任何异常 → 返回全候选(不截断)。

## 7. 工程细节

- **条件装配限制**(重要):每个模型服务是 `@ConditionalOnProperty havingValue=<strategy>`,**只有匹配全局 strategy 的那一个 bean 会实例化**。故 per-request 的 rank A/B override **只能在 `v1` 和"全局配置的那个模型"之间切**;override 到别的模型会静默回退规则(`reason=not_ready`)。配置的实验(v1 vs 全局模型)是有效的。
- **ONNX 加载**:`@PostConstruct load()`,任何 `Throwable` → `ready=false` → 回退规则。输出兼容 `[N,1]/[N]/[N,2]`,取最后一列为正类概率。
- **readiness 探针**:`activeStrategyReady()` 驱动 k8s `/actuator/health/readiness`——配了模型策略但 ONNX 没加载 → DOWN,k8s 不导流。
- **重训后**:必须 `mvn -pl recsys-rank clean install` 把 ONNX 重新打进 jar(单纯 `install` 会在 target/classes 留陈旧资源)。

## 8. 踩坑

1. **特征不一致 / 数据穿越**——离线用了在线拿不到的特征,或用了"未来"信息,离线 AUC 虚高、上线翻车。三大编码器 + as-of 采样就是防这个。
2. **DeepFM/id 模型用随机切分**(每个 id 都要在训练集见过),其 AUC 不能与 LightGBM 的时间切分 AUC 直接比——用 `eval` 任务公平对比。
3. **评估 ground-truth(评分≥4 留出正样本)等同 DeepFM 单标签**,故 DeepFM 在 NDCG/precision 领先,多任务模型在 HitRate@20/coverage/diversity/novelty/AUC 领先——是多目标权衡,非退化。
4. **ONNX 导出三坑**:合并外部 `.onnx.data` 成单文件(Java 从 classpath 按 `byte[]` 加载)、强制 `ir_version=9`、DIEN/TIGER/BGE 必须 `dynamo=False`(否则 chunk→Split/opset18 使 onnxruntime 加载失败)。

## 9. 面试要点

- **DeepFM vs DCN**:DeepFM 是 FM 二阶交叉 + DNN;DCN v2 的 Cross Network(`x_{l+1}=x0⊙(W·x_l+b)+x_l`)提供**显式、可控阶数**的高阶交叉。
- **MMoE vs PLE**:MMoE 多专家 + 每任务门控;PLE/CGC 进一步分"共享专家 + 任务专属专家",缓解多任务负迁移(跷跷板)。
- **ESMM 解决什么**:CVR 样本选择偏差——CVR 只在点击样本上有 label,ESMM 在全空间用 pCTCVR=pCTR·pCVR 建模绕开。
- **DIN/DIEN/SIM**:DIN 候选对历史 target-attention;DIEN 加 GRU+AUGRU 建模兴趣演化;SIM 用 GSU 类目硬检索把有效历史从 20 拉到 500。
- **PAL 位置去偏为什么优于"位置当特征"**:训练加位置塔、导出丢弃,在线零改动。
- **在线离线契约**:分桶取模、category 词表、序列 padding 必须逐位一致——三大编码器是契约载体。
