# C08 自然曝光契约核对记录

2026-10-15，ER-S08，基线 b9a6d8dc。沿既有 BACKEND_ARCHITECTURE / PLAN F08，不重新划分服务边界。自然图文阈值/有效期先异步询问后得到明确答复，已记录 C08-C；此前没有把广告 CPM 政策自动当自然分母政策。

## 已核对事实与范围

- RecommendOrchestrator 的缓存命中和新排序两处调用 ExposureLogger；后者向 user_behavior、Redis 和旧指标写“响应即曝光”。新 sandbox 候选方法必须跳过三种副作用，异常传播而非把未知结果固化为空交付。
- engine 拥有交付操作和自然资格，behavior 拥有接受的自然事实及 Outbox；即使当前库同实例也不跨 owner 直接写表。新事实与旧 user_behavior 分开，SANDBOX 不进入旧离线样本/实验分母。
- 当前 RecommendItem 无内容/模型版本，Item 无更新时间版本；不能伪造 actual model version。新交付保存当时文本内容的 hash、原分桶、排序分/召回解释；模型 lineage 标 UNVERIFIED，S23/S24 未完成前不能据此宣称可验证生产收益。
- recsys-common 仅 JDK + validation，不能为复用签名把 Jackson/web 或 ad 业务依赖引入 behavior。专用自然资格契约与纯 JDK 编码可放 common；engine/behavior 各自装配密钥环，不升级依赖。

## 核对后已冻结的协议

- 独立 ADMIN sandbox 路径：engine PUT/GET /api/sandbox/natural-deliveries/{operationId}，请求 userId 十进制字符串、kind RECOMMEND/SEARCH、size 1～50、scene、query（SEARCH 必填）；原请求与原结果固定，不能把编辑表单变成原操作的新语义。
- engine primary PG 保存 PREPARING/COMPLETE、请求、30 秒租约/fence、原响应。召回/内容读取在事务外；最终截断、去重、固定 slot、生成资格及完整响应，单条带 fence 更新原子提交后才能返回。提交前崩溃没有对外资格；提交后重试查原结果。缓存仅复用候选计算，不复用另一交付的资格。
- 资格绑定版本/用途/keyId、来源 SANDBOX、主体、操作/资格 UUID、user/item/slot、kind/scene、分桶、文本 hash、policyVersion/阈值/有效期、签发时间；固定顺序版本化 JDK 二进制编码 + HMAC-SHA256（独立至少 32 字节 key）。票据有长度上限、完整前缀签名、未知版本/多余字段拒绝。新 key 签发、旧 key 验证在途；所有实例时钟需同步，behavior 使用自己的 DB 时间受理。
- behavior PUT/GET /api/sandbox/natural-impressions/{receiptId}，body 与 C05 的观测字段一致；只认签名主体/上下文，不接受客户端 item/bucket/来源。gateway 两种路由先匹配此专属 behavior 路径，再匹配 engine 的 /api/sandbox/**。
- 同 receiptId 同摘要返回原结果，改负载 409、错主体 403。qualificationId 唯一事实；不同 receiptId 合法重复返回原 canonicalReceiptId/acceptedAt。结果 ACCEPTED / ALREADY_ACCEPTED / POLICY_NOT_MET / EXPIRED；无金额和账本字段，不把自然接受当收费。
- 回执键、资格并发锁、不可变自然事实、Outbox、原结果同 behavior PG 的 REQUIRES_NEW/READ_COMMITTED 五秒事务。锁后重新读取 DB 时间检查到期；事实与 Outbox 各一份，不依赖 Redis。故障整体回滚，503 未知可同键查询；GET 404 不代表确定未发生。
- 新事件 NaturalImpressionAcceptedV1：eventId=qualificationId、immutable-fact、SANDBOX、固定操作/user/item/slot/scene/bucket/policy/context hash、acceptedAt；不含票据/Bearer/认证主体。本片仅写待发，S15 可靠发送；不先尽力发送再补 Outbox。
- 功能默认关闭，签发与接受可分别暂停，查询/已存结果保留。新迁移为 engine 新版本和 behavior 自有历史，不改 S01～S07 已执行迁移。不自动清理事实/幂等/资格，保留期由后续容量/生命周期决策确定。

## 验证与待冻结点

真实 PG：同 ID 并发/同资格多 ID、改负载/错签名/错主体、到期及锁等待过期、各事务写点故障、重建服务查原结果；engine 丢响应后候选/内容变更仍原资格；旧路径和缓存行为回归；两种 gateway 路由、ADMIN 边界、未知结果测试。独立 schema 验证 engine 不写 behavior。

核对结果已提升为正式 CONTRACTS C08-A/B/C；用户已批准 natural-text-v1。后端真实 PG 验收已完成，浏览器和可靠发送分别由 S09/S15 继续；没有启用共享环境。

机制实施与验证已完成，见 [ER-S08_RESULT](ER-S08_RESULT.md) 与 [运行说明](NATURAL_EXPOSURE_RUNBOOK.md)。C08-C 已确认，父片后端验收完成；没有打开自然能力。
