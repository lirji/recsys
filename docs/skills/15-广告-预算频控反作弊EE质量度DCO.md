# 广告 · 机制大全(预算/频控/反作弊/EE/质量度/DCO/保量)

> 广告系统在"召回→竞价→计费"主干之外,还有一圈**守红线、控体验、防作弊、促探索**的机制。本篇一次讲清。每个都可开关、可优雅降级。

## 1. 预算与 Pacing(`PacingService`)

- **预算熔断**:广告主日预算耗尽即停投。消耗记 Redis `ad:budget:{adv}:{yyyymmdd}`(整数分,TTL 2 天),与在线 pacing 同源。
- **Pacing 平滑**:`ad:pacing:{adv}` 调 `pacingFactor` 抹平投放速度,避免预算上午就烧光(`pacedBid = effBid · pacingFactor`)。
- **降级**:Redis/DB 失败 → **fail-open**(不限预算,可用性优先)。

## 2. 频控(`FrequencyCapService`,仅 `/api/feed`)

- Redis 日维度限同广告/同广告主曝光(`ad:freq:{user}:{adId}:{day}`、`ad:freq:adv:...`,默认 3/8 次)。
- 只在混排 feed 生效(`FeedOrchestrator` 调用),搜索广告无频控。
- 降级:关闭 / Redis 失败 → 不限频。

## 3. 反作弊(`AntiFraudService`)

- **点击去重**:`ad:clicked:{req}:{adId}`(`setIfAbsent`),同一次曝光的重复点击不计费。
- **频次限制**:`ad:clk:rate:{user}:{min}`,每分钟超阈(默认 20)判无效。
- **归因校验**:点击必须能归因到一次真实曝光。
- 无效点击落 `ad_event` `INVALID_CLICK`,**不计费**——守计费公平。
- 降级:Redis 失败 → **fail-open**(判为有效,宁可漏放不误杀)。

## 4. 新广告 EE 探索(`ExplorationService`)

- 新广告没有历史 CTR,纯 eCPM 排序会永远排不上(冷启动饥饿)。
- UCB 加成抬升曝光不足广告的 `rankEcpm`:`boost = f(曝光量)`,coef 0.5、maxBoost 3。
- **只进排序不进计费**(守红线,自身 boost 不抬自身扣费)。
- 统计由离线 `ad-explore-stats` 物化 `ad:stats:{adId}` / `ad:stats:total`。

## 5. 精细化质量度(`QualityScoreService`,M7)

- 替代随机基线 `quality_score`,进 eCPM 计算。
- 离线 `ad-quality` 从 `ad_event` 聚合**相关性 / 经验 CTR / CVR 三因子**,纯函数 `QualityScore` 做**贝叶斯收缩**(小样本向先验收缩)+ 归一融合成围绕 1.0 的乘子 → Redis `ad:quality:{adId}`。
- 明细写 `eval/ad-quality-<ts>.csv` 供审计(计费相关,必须可审计)。
- 在线缺失退基线。与 EE 临时探索**正交**(质量度=长期性能,EE=临时探索)。

## 6. DCO 动态创意优化(`CreativeSelector`+`CreativeBandit`,M7)

- 竞价后,对每条竞得广告用 **UCB 多臂老虎机**按创意级 CTR(`ad:cstats:{adId}:{creativeId}`)从 `ad_creative` 多套创意里择优展示。
- **只换展示创意,不动排序/计费**。`ad_event.creative_id` 闭环归因。
- 开关 `recsys.ad.dco.enabled`(默认关),无创意退默认(creativeId=0)。**只展示 approved 创意**(审核闸门)。

## 7. 品牌保量 GD(`GuaranteedDeliveryService`+`GdPacing`,A4)

- 品牌合约 `ad_contract` 按投放进度挑**最落后**合约,置广告位首位、竞价让位、曝光 INCR 交付(`ad:gd:delivered:{contractId}`)。
- `GdPacing` 算交付进度 vs 时间进度的差(tol 0.02)。
- 开关 `recsys.ad.gd.enabled`(默认关),无合约退纯竞价。

## 8. 混排 Ad Load(`AdMixer`,纯函数)

- 广告按位次/密度混入自然推荐结果 + 去重,带"赞助"标记(`FeedOrchestrator` → `/api/feed`)。
- slots `[2,6,10]`,max 3(可 Nacos 热更)。自然结果耗尽即停。

## 9. fail-open vs fail-closed(降级哲学)

| 类别 | 机制 | 理由 |
|---|---|---|
| **fail-open**(可用性优先) | Pacing / 反作弊 / 定向 / 频控 | 基础设施挂了宁可多投/放行,不阻断广告 |
| **fail-closed**(正确性优先) | 召回 / 模型 / 校准 / DCO / GD | 拿不到数据宁可返回空/退基线,不出错误结果 |

`AdPipeline.run` 顶层 catch 保证任何请求都不崩(返回空广告)。

## 10. 面试要点

- **EE vs 质量度正交**:EE 是新广告临时探索加成(短期),质量度是历史性能(长期),都进 eCPM 但语义不同。
- **质量度为什么贝叶斯收缩**:小样本 CTR 噪声大,向先验收缩防抖;且计费相关必须可审计。
- **DCO 为什么只换创意不动排序**:排序/计费已定,DCO 是展示层优化,UCB 选最优创意。
- **反作弊/pacing 为什么 fail-open**:广告是收入,基础设施抖动不该整体停投;但计费公平靠 INVALID_CLICK 不计费守住。
- **GD 保量与竞价的冲突**:品牌合约需保量 → 让位竞价,是"保量优先级 > 即时收入"的取舍。
