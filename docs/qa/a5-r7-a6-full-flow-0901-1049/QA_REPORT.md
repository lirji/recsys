# A5 / R7 / A6 真实全流程验证报告

> **状态口径**：这是 2026-09-01 的最新实测证据。路线表中的 ✅ 表示功能代码已经落地，不等同于真实数据验收；A6 的真实闭环仍按本文“阻塞”结论管理。

## 结论

验证时间：2026-09-01（Asia/Taipei）
目标环境：localhost，持久化 PostgreSQL / Redis，网关 `http://127.0.0.1:9080`

| 范围 | 结果 | 结论摘要 |
|---|---|---|
| A5 多触点归因 | **通过** | 真实 `ad_event` 中 1 条转化、3 个触点完成 position attribution；MTA 信用守恒且确实发生跨广告拆分 |
| R7 Contextual Bandit | **通过** | 5,033 条真实曝光物化模型；在线 explain 观察到非零 Bandit 分项；模型缺失时安全回退为 0 |
| A6 延迟反馈 DFM | **阻塞** | 样本生成和 ONNX 技术链路可运行，但真实库仅 2 次点击，训练验证集为空；广告分片还缺线上查询所需字段，无法验证真实在线闭环 |

因此，`A5`、`R7` 可以补充“真实全流程已验证”的完成证据；`A6` 目前只能维持“代码/合成数据/组件契约已实现”，不能标记为真实全流程验证通过。

## 环境与真实数据基线

- Gateway、PostgreSQL、Redis、Nacos 及应用容器正常启动，Gateway health 为 `UP`。
- PostgreSQL 主库：`ad_event=187`、`user_behavior=105876`、`item=9742`、`item_embedding=9742`、`user_embedding=610`。
- `user_behavior`：`IMPRESSION=5033`、`CLICK=5`、`LIKE=1`、`RATING=100837`。
- `ad_event`：`IMPRESSION=184`、`CLICK=2`、`CONVERSION=1`。
- 两个广告分片各有 20 行 `ad` 数据。
- 验证开始前 Redis 中不存在 `bandit:model`，也不存在本次使用的 `recsys:tuning` 字段。

## A5：多触点归因

执行真实作业：

```bash
mvn -pl recsys-offline spring-boot:run \
  -Dspring-boot.run.arguments="--job=ad-attribution --days=0 --model=position"
```

结果：

- 作业成功处理 1 条转化，平均路径深度为 3 个触点。
- 生成 `recsys-offline/eval/ad-attribution-20260901-120507.csv`。
- CSV 共 3 个广告归因行：广告 33 / 21 / 15 的 MTA 信用分别为 0.4 / 0.4 / 0.2。
- `sum(conversions_last_touch)=1.0`。
- `sum(mta_credit)=1.0`，与源表 1 条转化一致。
- `sum(cta_credit)=0.0`、`sum(vta_credit)=1.0`，且每行都满足 `cta_credit + vta_credit = mta_credit`。
- `credit_delta` 分别为 0.4 / -0.6 / 0.2，证明信用并非只停留在 last-touch，而是发生了真实多触点拆分。

判定：**A5 真实全流程通过**。当前只有 1 条转化，足以证明链路与守恒，但不足以评估统计稳定性。

## R7：Contextual Bandit

执行真实作业：

```bash
mvn -pl recsys-offline spring-boot:run \
  -Dspring-boot.run.arguments="--job=bandit-stats --days=0"
```

离线物化结果：

- 处理 5,033 条真实曝光，其中正反馈 64。
- Redis `bandit:model` 的 `n=5033`、`lambda=1.0`。
- 特征顺序为 5 维：`item_pop_norm`、`item_avg_rating`、`user_act_norm`、`user_avg_rating`、`user_cat_affinity`。
- A 为 5×5 数值矩阵，b 为 5 维数值向量。
- 在线 `BanditScorer` 成功读取并求逆；非零在线打分也间接验证模型可用。

在线对比结果（同一 `userId=1`、`scene=home`、`explain=true` 请求）：

- 默认关闭：50 个候选的 Bandit 分项全部为 0。
- 临时开启 `fusion.bandit.enabled=true`、`weight=0.3`、`alpha=1.0` 后：50 个候选全部出现非零 Bandit 分项，值为 `0.008042729768262622`；返回分数同步增加约 0.008。
- 保持开关开启但移除模型、重启 rec-engine 清除模型缓存后：请求仍为 HTTP 200，50 个候选 Bandit 分项全部回退为 0。

观察项：本次 50 个候选的 Bandit 分项完全相同，原因是当前 Redis 没有 `feat:user:*` / `feat:item:*` 物化键，真实在线特征快照退化，探索信号缺少区分度。这不影响 R7 链路通过，但会影响实际探索效果。

判定：**R7 真实全流程通过**，同时需要补做特征物化后再评估排序区分度。

## A6：延迟反馈 DFM

### 真实样本生成

执行：

```bash
mvn -pl recsys-offline spring-boot:run \
  -Dspring-boot.run.arguments="--job=gen-ad-cvr-samples --days=0 --seed=42"
```

结果：

- 从真实库读到 2 次点击，成功映射到广告分片中的 item，写出 2 行样本，未发生 item 映射丢失。
- 标签为 1 正、1 负，但两行均为 `train`，没有 `valid` 行。
- 正样本的 `delay_days=-0.00003`。生成器按 `request_id + ad_id` 选择最早转化，但没有约束 `conversion.ts >= click.ts`，导致错误时序也会被标成正样本；训练器随后把负 delay 裁剪为 0，掩盖了数据问题。

### 训练与导出

训练器对这 2 行真实样本返回退出码 0，并完成 ONNX 导出和 ONNXRuntime 回读：

- 输入 `dense=[N,5]`、`sparse=[N,3]`，输出 `pcvr=[N,1]`。
- 4 轮训练的 `valid_nll` 均为 `nan`，随后按早停逻辑结束。
- 训练器没有因空验证集或 `nan` 指标失败，仍打印“导出完成”。这是训练质量门禁缺失，不能把本次模型视为有效产物。

### Java 模型契约与真实在线入口

- 恢复原有 20,000 行样本及模型后，`DfmCvrServiceTest` 3/3 通过：ONNX 加载成功、pCVR 在 `[0,1]`、缺模型安全回退。
- 运行中的 ad-serving 日志也确认现有 classpath DFM 模型能够加载。
- 但 `/api/search-ads?userId=1&q=action&size=3` 虽返回 HTTP 200，`ads=[]`；ad-serving 实际 SQL 报错：两片 `ad` 表均缺少 `review_status`。
- `recsys-offline/sql/02_ad_schema.sql` 和 `04b_ds1_bootstrap.sql` 已声明该字段，说明当前持久化库未应用到对应迁移版本。候选查询在进入 DFM pCVR 覆盖前已经失败。

判定：**A6 真实全流程阻塞**。样本生成器、训练器导出和 Java ONNX 组件分别可运行，但当前真实数据不能产出有效模型，线上 schema 也不能让请求到达模型消费阶段。

## A6 必须修复的验收门槛

按优先级建议：

1. 将 `02_ad_schema.sql` / `04b_ds1_bootstrap.sql` 中缺失的广告字段迁移应用到两片持久化库，并先验证 `/api/search-ads` 能返回候选。
2. 修正样本 join：只接受 `conversion.ts >= click.ts`，并明确多点击到一次转化的归属规则；对负 delay 直接失败或计数告警，不能静默裁剪。
3. 为训练器增加硬门禁：最小样本量、正负样本同时存在、train/valid 均非空、loss 必须 finite；任一不满足应非零退出且禁止覆盖模型。
4. 积累足量真实点击/转化后，重新执行 `真实事件 → 样本 → 训练 → 新 ONNX → AD_CVR_DFM_ENABLED=true → /api/search-ads`，并在响应或可审计日志中暴露本次实际使用的 pCVR 来源和值。

## 清理与恢复

- `bandit:model` 已恢复为验证前状态：不存在。
- 本次临时写入的 `recsys:tuning` 字段及空 hash 已删除。
- 原有 `ad_cvr_samples.csv`、DFM ONNX、schema、category vocab 已按 SHA-256 备份值完整恢复。
- Gateway 最终 health 为 `UP`。
- 未清空或造数，未执行 schema 迁移，未删除 Docker volume。
- A5 的归因 CSV 和本 QA 报告作为验证证据保留。
