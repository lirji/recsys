# 企业可靠性实施状态

2026-10-17 回合99fbfaf6完成必要整改ER-CI-FIX-01本地PASS：曝光浏览器按数据库上海日期种子当天/次日预算，确定性PG4项、真实宿主1项、Chromium4项和静态检查全部通过；详见 [整改结果](ER-CI-FIX-01_RESULT.md)。B1已在13f79e89提交/ff合并/push，CI38933333366终态success（8/8）；旧C15B CI38927688429保留failure，不重跑。新修复Git/CI见本轮delivery.json/fix-ci.json及实际Git。父S15B/S15仍IN_PROGRESS，下一业务片B2 READY，C15C后续，S22～25暂停；仅Sandbox，不部署。11保护文件/自然实现/旧CPA纳秒fixture均不变。

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
| ER-S09 | ER-S07、ER-S08 | CONTRACTS C09 / C09_DESIGN；自然推荐搜索浏览器 | 93 前端单测、5 自然 PG 浏览器、4 广告 PG/Kafka 回归、5 旧浏览器、50 后端专项；[ER-S09_RESULT](ER-S09_RESULT.md) | no | DONE |
| ER-D0/C10 | ER-S04、ER-S07、ER-S08、ER-S09 | CONTRACTS C10 / C10_DESIGN；原授权内最终混排/部分恢复/只读频控 | 不变更钱规则，沿用原前端审查 | no | DONE |
| ER-S10 | ER-D0/C10 | F10；common/ad/proto/engine/console，隔离PG与双模式浏览器 | 485后端/98前端、5混排+5自然+4广告及6旧浏览器通过；[ER-S10_RESULT](ER-S10_RESULT.md) | no | DONE |
| ER-D0/C11A | ER-S05、ER-S07、Q-03已确认 | C11_DESIGN / CONTRACTS C11A；精确点击报价 | 新模式不进入旧CPM捕获 | no | DONE |
| ER-S11A | ER-D0/C11A | 原S11前置；common-ad/ad纯候选、精确报价、V008 | 170项钱链路（含6项新真实PG），[ER-S11A_RESULT](ER-S11A_RESULT.md) | no | DONE |
| ER-D0/C11B | ER-S11A、Q-03已确认 | C11_DESIGN / CONTRACTS C11B；内部原子结算、V009、原账户共享 | 锁序/反作弊/期限/恢复契约已冻结 | no | DONE |
| ER-S11B | ER-D0/C11B | common-ad/ad；资格预占、双条件、反作弊、资金事实/Outbox、回收 | 197项钱链路（新增22真实PG+5worker），[ER-S11B_RESULT](ER-S11B_RESULT.md) | no | DONE |
| ER-S11C1 | ER-S11B | C11_DESIGN / CONTRACTS C11C1；ad V010、点击事件/Inbox及受控复用投递机制 | 216项钱链路（新增12PG+5Kafka+2配置），[ER-S11C1_RESULT](ER-S11C1_RESULT.md) | no | DONE |
| ER-S11C2 | ER-S11C1 | C11_DESIGN / CONTRACTS C11C2；ad/proto/engine、稳定点击资格获取/双模式接收与恢复 | 249项钱链路、577项全仓验证，见[ER-S11C2_RESULT](ER-S11C2_RESULT.md) | no | DONE |
| ER-S11 | ER-S11A | 原F11点击预占、曝光依赖、反作弊及捕获、双模式API | 完整报价/预占/双条件/可靠事件/双模式恢复闭环，见[ER-S11_RESULT](ER-S11_RESULT.md) | no | DONE |
| ER-S12A | ER-S11、Q-04已确认 | C12_DESIGN / CONTRACTS C12A；common-ad/ad CPA精确候选及V012不可变报价 | 262钱链路/55类全通过，详见ER-S12A_RESULT | no | DONE |
| ER-S12B | ER-S12A | C12_DESIGN / CONTRACTS C12B；ad V013、内部CPA业务事件/双条件/账本Outbox/到期回收 | 290钱链路/57类全通过，详见ER-S12B_RESULT | no | DONE |
| ER-S12C1 | ER-S12B | C12_DESIGN / CONTRACTS C12C1；独立CPA事件/Inbox投影/V014，复用有界传输 | 310钱链路/60类全通过，详见ER-S12C1_RESULT | no | DONE |
| ER-S12C2 | ER-S12B、ER-S12C1 | C12_DESIGN / CONTRACTS C12C2；严格广告主归属、独立caller与稳定双模式交付 | 355钱链路、45最终专项、14真实浏览器PASS，详见ER-S12C2_RESULT | no | DONE |
| ER-S12 | ER-S11、Q-04已确认 | 原F11；广告主认证转化业务键、原子捕获/迟到及双模式可靠事件 | A/B/C1/C2完整闭环，详见ER-S12_RESULT | no | DONE |
| ER-S13A | ER-S06、ER-S12 | C13_DESIGN / CONTRACTS C13A；common-ad/ad一致账户日快照与V016索引 | 同库PG并发/三族/差额/未知/隔离；只读无RPC；[验收](ER-S13A_RESULT.md) | no | DONE |
| ER-S13B | ER-S13A、C13B已冻结 | 原F12持久化有界对账任务/检查点/fence/审计 | 真实PG专项10项与资金链436项通过；[验收](ER-S13B_RESULT.md) | no | DONE |
| ER-S13C1 | ER-S13B、C13C已冻结 | V018、内部原事件续发及到期义务恢复 | 真实PG/Kafka 7项、资金链443项通过；[验收](ER-S13C1_RESULT.md) | no | DONE |
| ER-S13C2 | ER-S13C1 | 可信proto/RPC及offline入口 | 16项专项/32项相关回归通过；[验收](ER-S13C2_RESULT.md) | no | DONE |
| ER-S13C | ER-S13B、C13C已冻结 | 原F12受控续办、可信RPC及offline BillingReconciliationJob | C1/C2均通过；[完整验收](ER-S13_RESULT.md) | no | DONE |
| ER-S13 | ER-S06、ER-S12 | 原F12完整对账与恢复；A/B/C均必需 | A/B/C均完成；[完整验收](ER-S13_RESULT.md) | no | DONE |
| ER-S14 | ER-S13、C14五维规划/独立复审PASS | 原F13 Sandbox只读资金报表，详见C14_DESIGN | HTTP/PG/RPC13项、真实view-only授权、前端246项、双配置浏览器8项及截图；见ER-S14_RESULT | no | DONE |
| ER-S15 | ER-S06、ER-S08 | 原 F14：自然 Outbox + 旧 BehaviorService + 适用行为消费者；A/B/C 全部必需 | DB 成功 broker 失败、重复/崩溃/恢复后的下游效果；PG/Kafka/适用 Redis，见 [ER-S15_RESULT](ER-S15_RESULT.md) | no | IN_PROGRESS |
| ER-D0/C15A | ER-S06、ER-S08 | CONTRACTS C15 / C15_DESIGN；自然已接受事件发送的正式设计门禁 | 原源码/契约、范围覆盖、文件边界和 A01～A10 核验 PASS；仅设计，见 ER-S15_RESULT | no | DONE |
| ER-S15A | ER-D0/C15A | behavior 自然 Outbox V002、Mapper、relay/worker/配置；不改旧行为 | A01～A10 PASS；自然108项/行为16项，真实PG/Kafka+进程退出；[验收](ER-S15A_RESULT.md)，fixture不代产品消费者 | no | DONE |
| ER-D0/C15B | ER-S15A | 旧事件身份、原子写、CLICK重复修复/历史恢复/开关与消费者边界正式冻结 | D-B01～D-B09设计PASS；[结果](ER-D0-C15B_RESULT.md)，产品验收B01～B12未执行 | no | DONE |
| ER-S15B | ER-D0/C15B | 原 F14 旧 BehaviorService可靠事件；B1/B2均必需 | C15B B1本地PASS，B2未实现；B01～B12全部通过才关闭父片 | no | IN_PROGRESS |
| ER-S15B1 | ER-D0/C15B | 旧POST同库原子接受、CLICK原行重复修复、独立live/history可靠发送及审计重放 | 25项B1+16项回归PASS；[结果](ER-S15B1_RESULT.md)，Git/CI独立记录 | no | DONE |
| ER-S15B2 | ER-S15B1 | 有界显式历史清单/续办/审计取消，旧Seen/Flink topic guard与兼容 | B08～B12及受影响回归，含前10缺失项不能饿死后项；不替代S15C | no | READY |
| ER-D0/C15C | ER-S15B | 补 SeenItemsConsumer 的失败确认、毒消息/重放和格式兼容契约 | 明确新旧Seen的Redis失败确认/位点恢复；不能保留旧吞错入口 | no | TODO |
| ER-S15C | ER-D0/C15C | 原 F14 适用行为消费者，首先为真实已看投影；不吞失败确认 | Kafka/Redis（若有 Inbox 则 PG）：断连、重复、重平衡、提交窗口及恢复集合/offset | no | TODO |
| ER-S16 | ER-S06 | 原 F15 目录分片本库原子 Outbox、多分片暂停续办；正式契约待冻结 | 真实目录分片/Kafka；公共库不能冒充同库事务 | no | TODO |
| ER-S17 | ER-S16 | 原 F16 版本化目录投影/Inbox/墓碑/Redis 可靠刷新；正式契约待冻结 | PG/Kafka/Redis 重复、乱序、提交窗口与防复活 | no | TODO |
| ER-S18 | ER-S06 | 原 F17 日志补洞、持久检查点/并发领取；正式契约待冻结 | 真实 PG 低 ID 晚提交、重复扫描/恢复，不能以 MAX(id) 代表提交顺序 | no | TODO |
| ER-S19 | ER-S15 | 原 F18 Flink checkpoint、事件时间/迟到、Redis 幂等窗口投影；正式契约待冻结 | 真实重启/重放、旧窗口清理/特征对账；Kafka/Redis/隔离 checkpoint | no | TODO |
| ER-S20 | ER-D0 | 原 F19 主召回总 deadline、有界执行/缓存故障回源；正式契约待冻结 | 热点/断 Redis/慢通道与正常流量的负载资源预算 | no | TODO |
| ER-S21 | ER-S20 | 原 F19 非关键 shadow 有界过载丢弃；正式契约待冻结 | 负载验证主链路资源隔离，关键事件不得丢弃 | no | TODO |
| ER-S22～ER-S25 | 依赖见 PLAN | 不在本轮恢复范围 | 保持原范围，等待后续用户授权 | no | TODO |

首片 UI 延续既有样式，仅修金额/单位和待确认状态。涉及 `/advertiser` 列表、`/advertiser/:id`、`/search-ads` 及其图/重放/对比；测试核对未知与正常数据、窄屏及桌面展示。暂无 Runtime 新组件，git 交付与部署权限分开。


当前恢复入口：ER-CI-FIX-01本地DONE/PASS，Git及新源码CI按整改结果和round-99fbfaf6/delivery.json/fix-ci.json核对；B1交付与8项源码CI均已成功；下一片ER-S15B2 READY。B2须公平续办显式清单、审计取消与消费topic guard，随后C15C/S15C。父S15B/S15仍IN_PROGRESS，S22～25暂停；本回合交付后返回外层，不启动第二片或codex-flow。

## 当前有界整改 ER-CI-FIX-01（2026-10-17）

本地状态 DONE/PASS；Git/新CI按delivery.json/fix-ci.json核对，不据本地结果预先声称远程成功。依赖B1基线13f79e89，先修复已确认的C15B CI38927688429曝光浏览器ACCOUNT_MISSING，再进入S15B2。用户明确授权测试宿主和必要测试工具/验收文档，正常独立fix提交、ff合并、push；不改变产品预算日/金额/账本规则，不操作共享数据或部署，不修改旧CPA纳秒fixture和11保护文件。

- [x] 核对B1真实文件、25项新验收+16项回归、23源码指纹及Git已推送；11保护指纹一致。
- [x] CI38933333366最终completed/success，headSha=13f79e89，8个job全部成功；在新push前记录终态。
- [x] 曝光宿主使用产品同一数据库时钟，覆盖当天/次日隔离预算；保持100元、真实账本断言。
- [x] 确定性午夜/月末/年末PG验证4项PASS；完整门禁5 JUnit+4 Chromium、静态检查及原有浏览器回归PASS。
- [x] 同Agent分离只读复审、保护/源码指纹检查均PASS；6个独占容器退出，无本地测试会话。
- Git交付/新CI：实际分支、提交、合并、push与运行结论以本轮delivery.json/fix-ci.json为事实记录；新推送不得冒用B1的CI成功。

本轮证据`.dev/codex-flow-reliability/round-99fbfaf6/`；完成后下一片ER-S15B2，父片不变更为DONE。

确定性负对照已完成：同一数据库种子入口仅建当天时，4例均明确缺次日账户（4失败、0错误/跳过，midnight-red.xml）；最终双日种子4/4 PASS，真实门禁5 JUnit+4 Chromium全部PASS；详见 [ER-CI-FIX-01_RESULT](ER-CI-FIX-01_RESULT.md)。
