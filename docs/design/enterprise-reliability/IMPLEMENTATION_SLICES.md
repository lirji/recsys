# 企业可靠性实施状态

2026-10-15 用户授权开始实施。原 PLAN 的范围与稳定编号保留；当前按每片依赖的正式契约推进，不将尚未定义的接口视为 READY。

| ID | Needs | 正式契约 / Owner / 范围 | 验收及 Runtime | 并行 | 状态 |
|---|---|---|---|---|---|
| ER-D0/C01 | 用户实施授权 | CONTRACTS C01；设计 owner；旧预算和报价展示 | 已核对 writer 单位/计费触发及消费者，schema 不变 | no | DONE |
| ER-S01 | ER-D0/C01 | CONTRACTS C01；backend+frontend；PLAN F01，附带 DTO/列表详情/报价重放的直接消费者 | 21 项后端、9 项前端、2 项浏览器测试通过；证据见 [ER-S01_RESULT](ER-S01_RESULT.md) | no | DONE |
| ER-D0/CPM | 用户确认的首版政策 | 预算/定价/预占/交付/回执/事件的具体协议与迁移 owner | 正式契约、真实 PG 验证入口、权限和隔离范围可实施 | no | IN_PROGRESS |
| ER-D0/C02 | 已确认金额/时区/sandbox 政策 | CONTRACTS C02，TECH_SELECTION；预算接口/事务/版本迁移已冻结 | 不修改现有生产账户，不新增迁移框架 | no | DONE |
| ER-S02 | ER-D0/C02 | CONTRACTS C02；F02 + ad-common DTO、billing proto、advertiser API/gRPC 及 ad-serving adapter | 43 项钱链路测试通过，含真实 PG 11 项；[ER-S02_RESULT](ER-S02_RESULT.md) | no | DONE |
| ER-D0/C02A | 已确认首个 CPM 闭环 | CONTRACTS C02A；首版 CPM/GSP，其他新计价版本后续扩展 | 旧浮点展示与新精确账务边界分离 | no | DONE |
| ER-S02A | ER-D0/C02A | CONTRACTS C02A；PLAN F02A；竞价精确边界、报价持久化与 proto 快照 | 57 项钱链路通过，新增 14 项（含真实 PG 5 项）；[ER-S02A_RESULT](ER-S02A_RESULT.md) | no | DONE |
| ER-D0/C03 | ER-S02, ER-S02A | CONTRACTS C03；预占状态与固定期限、数据库锁序/原子性 | 内部用例先行，RPC 编排归 ER-S04 | no | DONE |
| ER-S03 | ER-D0/C03 | CONTRACTS C03；PLAN F03；ReservationService/Repository、ExpiryJob、V003 | 74 项钱链路通过，新增 17 项（真实 PG 12）；[ER-S03_RESULT](ER-S03_RESULT.md) | no | DONE |
| ER-D0/C04 | ER-S03 | CONTRACTS C04；整次交付/签名准备/双模式 owner | 搜索先行，feed 由 S10 接入 | no | DONE |
| ER-S04 | ER-D0/C04 | CONTRACTS C04；PLAN F04；engine operation + ad preparation + RPC/HTTP | 103 项钱链路通过，本片新增 29（真实 PG 14）；[ER-S04_RESULT](ER-S04_RESULT.md) | no | DONE |
| ER-D0/C05 | ER-S04 | CONTRACTS C05；回执原子捕获及账本/Outbox | 图文 CPM 政策已确认；新增回执开关默认关闭 | no | DONE |
| ER-S05 | ER-D0/C05 | CONTRACTS C05；F05；ad receipt/ledger/outbox + RPC/gateway/HTTP | 126 项钱链路通过，本片新增 23（真实 PG 13）；[ER-S05_RESULT](ER-S05_RESULT.md) | no | DONE |
| ER-D0/C06 | ER-S05 | CONTRACTS C06；可靠投递和逐账本投影 | 复用 Kafka，真实 PG/broker 隔离验证 | no | DONE |
| ER-S06 | ER-D0/C06 | CONTRACTS C06；F06；ad relay/投影/V006、Kafka 配置和 CI | 144 项钱链路通过，新增 18（PG 9、Kafka+PG 4）；[ER-S06_RESULT](ER-S06_RESULT.md) | no | DONE |
| ER-D0/C07 | ER-S06 | CONTRACTS C07；既有前端 PLAN 与 C04/C05 真实 API | 显式 sandbox 分支、主体隔离与可见测量 | no | DONE |
| ER-S07 | ER-D0/C07 | CONTRACTS C07；F07；console 测量/队列/搜索页与脱敏 | 81 项前端单测、4 项新浏览器 + 真实 PG/Kafka、3 项旧页面回归；[ER-S07_RESULT](ER-S07_RESULT.md) | no | DONE |
| ER-D0/C08 | ER-S07 | CONTRACTS C08-A/B/C 已冻结；自然图文政策已确认 | 正式装配要求 natural-text-v1；默认关闭 | no | DONE |
| ER-S08 | ER-D0/C08 | F08；engine 最终自然资格与 behavior 原子回执/Outbox | 50 项专项（23 项真实 PG）和 12 项旧回归；[ER-S08_RESULT](ER-S08_RESULT.md)；默认关闭 | no | DONE |
| ER-S08A | ER-D0/C08 | F08；common、engine、behavior 参数化闭环及双路由 | 机制纳入父片 50 项专项（23 项真实 PG）、12 项旧回归；[ER-S08_RESULT](ER-S08_RESULT.md) | no | DONE |
| ER-S09～ER-S25 | 依赖见 PLAN | 逐片提升对应 CONTRACTS，范围不扩大 | 验收见 PLAN 与 VALIDATION_PLAN | no | TODO |

首片 UI 延续既有样式，仅修金额/单位和待确认状态。涉及 `/advertiser` 列表、`/advertiser/:id`、`/search-ads` 及其图/重放/对比；测试核对未知与正常数据、窄屏及桌面展示。暂无 Runtime 新组件，git 交付与部署权限分开。
