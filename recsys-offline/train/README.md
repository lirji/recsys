# Track D · 离线 CTR 训练(LightGBM → ONNX）

把行为日志训练成排序模型,导出 ONNX 给在线 `OnnxRankService` 加载。**离线训练与在线打分共用同一套特征**(Java `FeatureAssembler` ↔ 本目录 `samples.csv` 表头 ↔ `feature_order.json`,三处顺序必须一致)。

## 全流程

```bash
# 0) 前置:infra 起着、行为已导入(Track E)
docker compose up -d                      # postgres + redis
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="/Users/liruijun/personal/devUtils/apache-maven-3.9.12/bin:$PATH"
mvn -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments=--job=import-behavior   # 若未导

# 1) 物化离线特征到 Redis feat:*(在线/离线特征同源)
mvn -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments=--job=build-features

# 2) 构造训练样本 → recsys-offline/train/samples.csv
mvn -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments=--job=gen-samples

# 3) 训练 + 导出 ONNX → recsys-rank/src/main/resources/model/model.onnx
cd recsys-offline/train
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python train_lgbm.py

# 4) 在线启用 ONNX 排序
cd ../..
RANK_STRATEGY=onnx mvn -pl recsys-rec-engine spring-boot:run   # 需先 mvn install 让 rank 带上 model.onnx
```

## 特征(`FeatureAssembler.FEATURE_ORDER`)

| 顺序 | 名称 | 含义 |
|---|---|---|
| 0 | item_pop_norm | 物品热度(评分数 log 归一) |
| 1 | item_avg_rating | 物品平均分 /5 |
| 2 | user_act_norm | 用户活跃度 log 归一 |
| 3 | user_avg_rating | 用户平均打分 /5(打分偏置) |
| 4 | user_cat_affinity | 用户对该物品类目的历史均分 /5(交叉特征) |

改特征要**同步**三处:`FeatureAssembler`(Java)、`BuildFeaturesJob` 写入、本脚本/`feature_order.json`,然后重训。

## 已知简化

- **负样本**:MovieLens 无曝光日志,用「按热度采样的未评分物品」近似曝光未点击。
- **轻度数据穿越**:特征用全量历史(含目标交互本身)计算,离线 AUC 偏乐观;生产应按事件时间 as-of 取特征。
- 模型加载失败时在线自动回退规则排序(`RankRouter`),不影响可用性。
