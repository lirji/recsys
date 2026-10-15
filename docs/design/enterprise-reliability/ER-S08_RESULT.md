# ER-S08：自然资格与原子回执参数化机制

2026-10-15；基线 `b9a6d8dc`，分支 `feat/natural-exposure-receipts`；[C08-A/B](CONTRACTS.md)。**ER-S08 / ER-S08A DONE / PASS**。用户已明确自然图文沿用 50%/1s/hidden 重置/5min，记录 C08-C 的 natural-text-v1；没有启用共享环境端点或切换生产。

## 实现与审查

- common 维持纯 JDK：不可变资格、显式 Policy、用途分离的 HMAC nq1 票据、严格长度/毫秒/大 ID、验签后解码；固定二进制摘要和 v1 golden 防止 key 轮换、JSON 命名或后续编码变化破坏幂等。无新依赖、无 ad→behavior 领域耦合。
- engine 新纯候选分支跳过 ExposureLogger/Redis 曝光键/旧分母；缓存和新排序两处都清掉旧 exposureId，旧 API 原曝光行为兼容。新交付原请求/政策先保存，再短事务领取 fence；外部计算不持锁；去重截断后绑定内容 hash、原桶、最终位置、独立资格，完整结果提交后才返回。模型 lineage 明确 UNVERIFIED。
- behavior 在独立 REQUIRES_NEW / READ_COMMITTED / 5 秒事务内领取 receipt、锁资格、读取新鲜 DB 时间、写唯一事实+Outbox+首次结果；无网络/缓存副作用。PK/唯一键、提交时完整性约束、canonical receipt/时间复合 FK 和不可变触发器共同保护，防半成品/事实无事件。不同 receipt 的合法重复引用同一事实。
- 新 engine V002 与 behavior V001 独立迁移/SHA 历史；没修改已执行迁移、旧表或共享数据库。两个 capability 开关默认关闭；Policy/key 完整显式配置、启动快照；暂停接受仍能恢复已提交结果，配置对象 toString 脱敏。
- HTTP owner/网关均 ADMIN；JSON 字段严格类型、禁止终端归因字段；403/409/422/503 区分，错误显式 application/json。两模式网关按 behavior 专属路径优先匹配。

审查重点已核对：只处理 SANDBOX；签名/主体/Policy 一致；无跨 owner 写表；数据库锁后才判过期；不同唯一键竞争不漏事件；旧 fence 不可覆盖；发布能力仍留 S15；源码/错误/配置输出不含可复用 token/key。魔法数字告警均为 C08 明确的资源上限/比例单位/事务上限，不是未批准业务政策；事务告警由真实 PG 故障窗口与源码锁序复核关闭。

## 验证

| 验收 | 方法 / 实际结果 |
|---|---|
| 资格 wire/身份/期限/密钥轮换 | 10 项 common 测试（含独立 Python 编码 golden 的 Java 读取/字节同一与正式政策拒绝漂移）；PASS |
| engine 原子冻结与恢复 | 9 项真实 PG：已批准政策五分钟期限、提交后丢 ACK、候选/内容/政策变化、失败未冻结、空与异常区分、并发领取、旧 fence、不可变历史、不同交付资格隔离；PASS |
| behavior 不重不漏与恢复 | 14 项真实 PG：已批准政策临界接受/阈值不足/hidden 拒绝、同/异 receipt 并发、改上下文/负载/身份/签名拒绝、政策拒绝与到期、锁等待过期、claim/fact/outbox/result 四写点回滚、重建查询、key/政策/开关变化、外层回滚仍已提交、DB 完整性与注释；PASS |
| 候选副作用隔离和旧兼容 | 3 项真实 RecommendOrchestrator 方法测试：缓存、新排序、异常；自然分支不调用旧曝光，旧分支继续原身份生成/曝光；PASS |
| API / 配置 / 网关 | 两 owner 各 4 项 HTTP + 2 项配置，严格类型/权限/安全错误/默认关闭；2 项 gateway 权限与双路由；PASS |
| 旧链路回归 | 12 项既有用例：旧 delivery/impression HTTP 共 6、ExposureLogger 1、BehaviorService 4、真实 PG 旧点击幂等 1；PASS |
| Code Hygiene | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：根 Maven 无 canonical formatter/static-analysis 命令，工具静态扫描/diff 检查通过；事务和数值 advisory 已人工复核。没有新建格式化工具链 |

日志：`/tmp/recsys-er-s08-approved-policy.log`（最终 50 项专项、0 失败/0 跳过），`/tmp/recsys-er-s08-legacy.log`（12 项旧回归），`/tmp/recsys-er-s08-hygiene.json`。此前机制完整运行 46 项、增量 golden 与配置回归也通过；最终正式政策变更后已整组重跑，不以重复次数充当覆盖。

HTTP 测试发现异常 handler 在无 Accept 请求下可能协商 XML，现明确 JSON Content-Type；保留错误码断言。golden 使用固定测试 key 和 test-only 政策，不含真实秘密。真实 DB 测试使用临时 PostgreSQL 16 容器，未使用共享 dev_infra；未做进程 SIGKILL、生产副本故障或容量测试。

## 状态、限制与后续

自然政策已经用户确认，正式装配只允许 natural-text-v1 的 5000bps/1000ms/300000ms；底层机制测试使用的 6000/1200/120000 明确 test-only，不得用于启用配置。S09 继续自然推荐/搜索浏览器。本片没有 UI 变动，因此视觉验证 N/A；engine→behavior 跨进程浏览器联调尚未执行，不能用分 owner 的 PG/HTTP 测试冒充该证据。

Outbox 已可靠落表，尚未投递 Kafka；自然事实尚未进入正式实验/训练样本。原旧链路风险仍存在，父计划未完成。独立 CI 新增 natural-exposure-gate，远程结果在正常 Git 交付后核对。运行/迁移/未知结果/暂停与保留说明见 [runbook](NATURAL_EXPOSURE_RUNBOOK.md)。继续原 DAG 的 S09 主路径，不因本片完成停止整个任务。

SKILL_HANDOFF: backend-implementation → implementation-validation → update-progress-docs；slice ER-S08 / ER-S08A；status=COMPLETED，gate=PASS；父 ER-S08 政策门禁已满足。Git 交付只涵盖默认关闭、已验证的机制和对应文档，不以此授权开启采集。
