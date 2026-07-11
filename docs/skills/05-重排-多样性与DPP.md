# 重排 · 多样性与 DPP

> **解决什么**:精排只管"准",但结果全是同一类型会腻。重排负责**体验和业务规则**——多样性、打散、去重、冷启动探索。
> 本项目做成**可插拔策略**(`Reranker` 接口 + `RerankRouter` 按分层 A/B 的 rerank 层选择),4 个策略。

## 1. 四策略

`RerankRouter`(未知策略默认 `diversity`)路由到:

| 策略 | 类 | 原理 |
|---|---|---|
| `diversity` | `DiversityReranker` | 同类目在结果中不超过 `maxSameCategory` 个;不足条数时放宽补齐 |
| `mmr` | `MmrReranker` | `next = argmax[λ·相关性 − (1−λ)·与已选最大相似]`,λ=0.7 |
| `dpp` | `DppReranker` | **行列式点过程**(Chen 2018 fast-greedy MAP,Cholesky 增量),相关性与多样性联合最优 |
| `none` | `NoopReranker` | 严格按相关性截断(对照组) |

**相似度来源**:MMR/DPP 优先用 `item_embedding::text` 余弦;无向量退化为"同类目=1"。

## 2. DPP 为什么强

MMR 是贪心的"相关性 − 多样性"线性权衡;**DPP 用核矩阵 `L` 的行列式**度量一个子集的"质量×多样性":
```
L_ij = q_i · S_ij · q_j     # q=相关性质量, S=相似度
P(子集) ∝ det(L_子集)
```
行列式几何上是"体积"——向量越正交(越不相似)体积越大。选 `argmax det` 天然平衡质量与多样性。Chen 2018 的 fast-greedy 用 Cholesky 增量更新,把每步复杂度降到可在线服务。本项目 `DppReranker` 就是这个 fast-greedy MAP。

## 3. 冷启动强制强多样性

冷用户(见 [06](06-冷启动.md))被编排层强制走 `diversity` + `maxSameCategory=1`,最大化兴趣探索面,不受实验变体影响。

## 4. 已看过滤

重排前有 `SeenFilter`(`recsys.filter.seen-enabled`):过滤用户已曝光/交互物品。种子来自 Redis `seen:{user}`(Kafka `behavior-events` → `SeenItemsConsumer` 物化)。**若过滤后候选为空,放弃过滤而非返回空**(可用性优先)。

## 5. 踩坑

1. **打散过度牺牲相关性**——`maxSameCategory`/λ 可配置、可做 A/B。
2. **MMR/DPP 依赖物品向量**——语料未全量灌向量时务必有"同类目"退化路径,否则无向量物品多样性失效。
3. **DPP 数值稳定性**——核矩阵近奇异时 Cholesky 会崩,需正则/退化保护。

## 6. 面试要点

- **MMR vs DPP**:MMR 线性贪心权衡;DPP 用行列式(子集体积)联合建模质量+多样性,理论更优、可 fast-greedy 在线化。
- **重排为什么可插拔**:多样性是体验/业务层,需按场景 A/B(feed vs 搜索多样性诉求不同)。
- **冷启动为什么强制强多样性**:无先验时最大化探索面,快速定位兴趣。
