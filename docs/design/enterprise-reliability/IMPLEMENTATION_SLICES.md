# 企业可靠性实施状态

2026-10-18 用户继续授权 ER-S19、ER-S20、ER-S21：三片实现与本地验收均DONE/PASS，分别39/45/52项，见ER-S19_RESULT、ER-S20_RESULT、ER-S21_RESULT。S19 c6b56ddb、S20 bcf59b2b已正常合并推送main，S21待本轮Git交付；实际交付和精确CI状态见`.dev/er-s19-s21/checkpoint.json`。此前S17/S18已完成，保护清单同恢复目录；本轮不部署、不启用共享可靠模式/影子、不启动S22～S25。

2026-10-17 ER-S16B必要实现、本地真实验收与分离复审DONE/PASS，父ER-S16的A/B完整闭环均满足。最终同一次目录门禁141项/17类零失败错误跳过（15:10），另真实status manage权限专项PASS；见[ER-S16B_RESULT](ER-S16B_RESULT.md)。A已交付6fe79594，精确CI38983151163全部9门禁成功，目录121项/14类PASS；保留设计5c0f2901。B提交、正常ff/push及精确新CI见`.dev/codex-flow-reliability/round-7fe6f115/delivery.json`，运行终态另见同目录checkpoint/handoff。S15有效证据复用。B交付后返回父，下一片S17～21，S22～25暂停。

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
| ER-S15 | ER-S06、ER-S08 | 原 F14：自然 Outbox + 旧 BehaviorService + 适用行为消费者；A/B/C 全部必需 | DB 成功 broker 失败、重复/崩溃/恢复后的下游效果；PG/Kafka/适用 Redis，见 [ER-S15_RESULT](ER-S15_RESULT.md) | no | DONE |
| ER-D0/C15A | ER-S06、ER-S08 | CONTRACTS C15 / C15_DESIGN；自然已接受事件发送的正式设计门禁 | 原源码/契约、范围覆盖、文件边界和 A01～A10 核验 PASS；仅设计，见 ER-S15_RESULT | no | DONE |
| ER-S15A | ER-D0/C15A | behavior 自然 Outbox V002、Mapper、relay/worker/配置；不改旧行为 | A01～A10 PASS；自然108项/行为16项，真实PG/Kafka+进程退出；[验收](ER-S15A_RESULT.md)，fixture不代产品消费者 | no | DONE |
| ER-D0/C15B | ER-S15A | 旧事件身份、原子写、CLICK重复修复/历史恢复/开关与消费者边界正式冻结 | D-B01～D-B09设计PASS；[结果](ER-D0-C15B_RESULT.md)，产品验收B01～B12未执行 | no | DONE |
| ER-S15B | ER-D0/C15B | 原 F14 旧 BehaviorService可靠事件；B1/B2均必需 | B1/B2及新增非法快照删除边界整改均本地PASS；见ER-S15B2-FIX-01_RESULT；C仍必需 | no | DONE |
| ER-S15B1 | ER-D0/C15B | 旧POST同库原子接受、CLICK原行重复修复、独立live/history可靠发送及审计重放 | 25项B1+16项回归PASS；[结果](ER-S15B1_RESULT.md)，Git/CI独立记录 | no | DONE |
| ER-S15B2 | ER-S15B1 | 有界显式历史清单/续办/审计取消，旧Seen/Flink topic guard与兼容 | 原18/43+24项及边界整改PG15+Kafka2 PASS；无问题覆盖排除已知非法源，见ER-S15B2-FIX-01_RESULT | no | DONE |
| ER-D0/C15C | ER-S15B | C15_DESIGN末节；旧Seen+live/history确认、隔离/重放/起点/Redis丢集合边界 | D-C01～D-C09设计PASS；见ER-D0-C15C_RESULT；C1产品结果另见ER-S15C1_RESULT | no | DONE |
| ER-S15C | ER-D0/C15C | 原F14适用消费者；C1旧Seen及C2新版均必需 | C1/C2全部适用C01～C18 PASS，最终完整行为83项；见ER-S15C2_RESULT | no | DONE |
| ER-S15C1 | ER-D0/C15C | 旧Seen完整恢复闭环；engine-seen V001/Mapper/手动ACK/持久预算/隔离/命令与起点 | 适用16项PASS；最终engine33项、未变门禁44复用；[结果](ER-S15C1_RESULT.md) | no | DONE |
| ER-S15C2 | ER-S15C1 | 独立LIVE/HISTORY产品listener、历史Seen与父片回归 | C01～C18全部PASS；最终83项/14类，B2真实history→产品Seen→Redis→Replica；见ER-S15C2_RESULT | no | DONE |
| ER-D0/C16 | ER-S06、用户本轮授权 | C16_DESIGN；实际分片/命令/事件/暂停/兼容及S17必要接口 | D16-01～09同Agent分离只读PASS；独立设计5c0f2901已随A正常交付；结果ER-D0-C16_RESULT | no | DONE |
| ER-S16 | ER-S06、ER-D0/C16 | 原F15；A单广告可靠闭环、B广告主/多分片暂停均必需 | A/B全适用验收PASS；最终完整141项/17类和真实权限专项，见ER-S16B_RESULT；生产者边界不替代S17 | no | DONE |
| ER-S16A | ER-S06、ER-D0/C16 | 单广告真实写口/本库Outbox/恢复/原事件Kafka闭环；含准入与基线 | C16-01～12/16～20适用项PASS；完整114、纠偏定向26、实际权限及分离复审，见ER-S16A_RESULT；最终精确6fe79594目录CI121项/14类PASS | no | DONE |
| ER-S16B | ER-S16A | 真实广告主暂停/恢复及跨owner显式批次，可查部分结果/续办 | C16-13～15、受影响A回归及V001升级PASS；最终141项/17类与真实权限，见ER-S16B_RESULT | no | DONE |
| ER-S17 | ER-S16、C17设计PASS | 原 F16；C17_DESIGN / CONTRACTS C17；ad-serving catalog/Mapper/XML/追加迁移/配置/真实测试 | C17-01～11真实PG/Kafka/Redis和配置/回归32项PASS；ER-S17_RESULT | no | DONE |
| ER-S18 | ER-S06 | 原 F17；C18_DESIGN、offline有界核对/持久检查点/并发领取 | C18-01～07真实PG专项19项与相关回归30项PASS；ER-S18_RESULT | no | DONE |
| ER-S19 | ER-S15 | 原 F18；C19_DESIGN 的显式可靠模式、持久checkpoint、事件时间与完整窗口投影 | C19-01～08真实恢复及兼容/读取/产物39项PASS；ER-S19_RESULT | no | DONE |
| ER-S20 | ER-D0 | 原 F19；C20_DESIGN统一deadline、有界执行/准入与缓存回源预算 | C20-01～07 PASS；45项验证，见[ER-S20_RESULT](ER-S20_RESULT.md) | no | DONE |
| ER-S21 | ER-S20 | 原 F19；C21_DESIGN影子线程/队列/输入/期限有界，关键事件保留原可靠路径 | C21-01～04 PASS；52项验证，见[ER-S21_RESULT](ER-S21_RESULT.md)；同进程物理资源仍共享 | no | DONE |
| ER-S22～ER-S25 | 依赖见 PLAN | 不在本轮恢复范围 | 保持原范围，等待后续用户授权 | no | TODO |

首片 UI 延续既有样式，仅修金额/单位和待确认状态。涉及 `/advertiser` 列表、`/advertiser/:id`、`/search-ads` 及其图/重放/对比；测试核对未知与正常数据、窄屏及桌面展示。暂无 Runtime 新组件，git 交付与部署权限分开。


当前恢复入口：C1与C2及原A/B必要验收均PASS，父S15按原范围DONE。C2实现a2ca663c与设计/C1提交独立，CI预算单行修正另作逻辑提交；本回合实际ff/push和新CI状态读取round-6ebaf7da/delivery.json、ci-snapshot.json。完成后返回父协调者，下一回合C16正式设计，之后S16～21；不递归启动下一片。

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

## 当前回合8395c172：ER-S15B2

状态本地DONE/PASS；复用B1和跨日fixture有效验证，前置CI38937727953已8门禁全成功。授权与边界见根恢复入口；原S-ID保持，父S15必须继续C15C/S15C。

- [x] 读取正式C15B，核对Git及11保护/原迁移/已交付源码指纹；冻结持久访问序号轮转细节。
- [x] 追加V002、Mapper XML、显式恢复/取消/真实报告；旧入口guard及类说明。
- [x] PG公平轮转、晚提交、并发、原子回滚/恢复、漂移/删除/审计、约束/迁移；真实Kafka原history发送恢复。43项完整行为门禁PASS，B2新18项。
- [x] 旧Seen/Flink格式兼容、保留topic误配拒绝、默认关闭及受影响B1/HTTP回归：主门禁43项和补充24项均零失败/错误/跳过。
- [x] 同Agent分离只读验证、hygiene/静态、文档、保护/运行检查PASS；hygiene仅工具配置限制。正常Git及新CI结论见本回合delivery.json/ci-snapshot.json，不能把本地PASS当远程全绿。

证据`.dev/codex-flow-reliability/round-8395c172/`，下一业务步骤ER-D0/C15C；本回合不启动第二片。

## 当前回合9fb853b6：B2非法快照删除边界复核

状态本地DONE/PASS；父发现已真实PG复现后修复（红3例/1失败，绿17项全PASS）。B2原证据保留，不重做整片；Git/CI交付仍须真实结果。

- [x] 核对B2 Git/验证、16源码和11保护指纹；读取父审查及正式契约。
- [x] 未改产品代码前真实PG复现，明确分类优先级与合法删除对照；原不变/漂移对照通过。
- [x] 最小分类修正/契约澄清、真实PG15+Kafka2=17项PASS；同Agent分离只读验证PASS，无事实回写。
- [x] 保护/现场核对PASS，6个独占容器均退出；正常Git交付以本回合delivery.json为准，前置CI终态见b2-ci.json，新源码CI见ci-snapshot.json。推送前必等前置终态，远程未绿不写全绿。

证据`.dev/codex-flow-reliability/round-9fb853b6/`；本回合不同时推进C15C设计或消费者实现。


## 当前回合89a217e3：ER-D0/C15C设计

- [x] 核对上轮真实PG红3/1失败与绿PG15+Kafka2、10个交付文件、11保护、旧迁移/fixture、Git及父只读笔记；有效验证不重跑。
- [x] 源码/实际依赖/官方3.1.4机制调查，比较方案；旧Seen+live/history全部纳入正式设计，C1/C2串行有界计划。
- [x] 完整SHA对应CI38947474433终态success、8/8门禁通过；无未处理失败，无取消/重跑。
- [x] D-C01～D-C09分离只读设计验证、文档链接/边界/保护核对PASS。
- [x] 本地独立设计交付范围和恢复入口已冻结；实际提交SHA/结果见本回合delivery.json，不合并/push，下一实施回合复用分支。

采用backend-architecture-design、implementation-slicing、project-documentation、update-progress-docs和task-git-delivery；详细C01～C18仍UNVERIFIED，不把设计PASS变成产品DONE。S15～21整体未完成，S22～25暂停。

## 当前回合282d2b60：ER-S15C1实施/验证

- [x] 核对679274fd设计提交、10文件指纹、11保护、前置源码CI38947474433终态成功。
- [x] 父审查1/3补充最小正式协议；第2项复用已有ENROLL条款。
- [x] 旧listener、专用factory、迁移/Mapper、PG预算/隔离、初始化/恢复命令代码已实现；实际产品链路进入最终门禁。
- [x] 代表真实链路C01 PASS：16条正反馈+1条无效果，offset17；PG4、旧兼容2、配置3 PASS。
- [x] 首轮故障矩阵23项18 PASS/5失败已定位；Redis重启实际端点变化和初始化竞争已修。targeted-faults-2.log的7项全部PASS，原失败证据保留。
- [x] behavior-final-2完整门禁73项PASS（engine29、behavior42、streaming2）；依赖/内部错误分类、真实容器commit窗口、Spring装配/指标已补。
- [x] R4真实PG红绿、R5实际删/越界位点、R6隔离提交后JVM退出全部PASS。最终engine五类33项PASS（engine-final.log）；Mapper只补中文注释，经源码还原摘要核对及最终编译PASS。
- [x] 最终分离只读验证、文档、Hygiene（无formatter/独立静态分析限制）PASS；独立实现提交/正常ff/push/新CI的实际状态统一读取delivery.json/ci-snapshot.json，不以本地验证推断远端结果。

原迁移、11保护、.dev/target与历史正文保留。当前不部署或共享启用；C1 DONE/PASS，C2 READY，父S15C/S15仍未完成。

## 当前回合6ebaf7da：ER-S15C2

- [x] C1源码/实际验收/Git/11保护核对，复用原分支。
- [x] 未知recordId真实PG接口回归；已有NOT_FOUND护栏不重复整改。
- [x] 三流参数化与LIVE/HISTORY严格解码、独立产品listener。
- [x] C01～C18适用真实验收及behavior→engine测试方向的原history全链路。
- [x] 分离审查/文档/保护/前置CI终态/本地逻辑提交；正常ff/push与新CI精确状态见delivery.json/ci-snapshot.json。

本回合最终完整门禁83项/14类PASS，详见full-behavior-result.json，22项被测源码冻结。恢复核对11保护/37旧迁移fixture/历史正文与83项XML一致；前置C1 CI8/8成功。请求2728fdeb已明确批准behavior CI超时12→20分钟；仅此单行，YAML/语义逐项比对及diff PASS，不重复产品测试。C2独立提交a2ca663c保留，CI修正/文档另作逻辑提交；Git/新CI实际动作以本回合delivery.json/ci-snapshot.json为准。

## 当前回合301afed4：ER-D0/C16正式设计

- 保留原PLAN F15/ER-S16及ER-S06依赖，不扩大到S17实现/部署/S22～25。父只读索引已逐项核对实际源码；primary是ShardingSphere，业务与子资源按不同键分库。
- 正式稿[C16_DESIGN](C16_DESIGN.md)冻结单物理库事务/本库事实、持久排他命令、完整快照/版本、暂停fanout/批次、起点与默认关闭；两条串行纵向片A/B分别闭合业务结果。同Agent分离只读D16-01～09全部PASS，ER-S16A READY；产品验收全部UNVERIFIED。
- 原目录分支`feat/er-s16-catalog-outbox`，基线686454dc；不新worktree。本轮只独立本地设计提交，下回合复用此分支实施S16A，通过必要验证后按持续授权正常ff/push；推送前须核对前置源码CI终态，不取消有效证据。
- 恢复/审查/提交证据：`.dev/codex-flow-reliability/round-301afed4/`；设计结果见[ER-D0-C16_RESULT](ER-D0-C16_RESULT.md)。11保护、37旧迁移/fixture、CODEX_PROGRESS历史正文和原用户改动保留；无需重跑源码未变的S15验收。

## 当前回合25e0dd1e：ER-S16A实施（PARTIAL / IN_PROGRESS）

- 最终C16、父只读审查及独立设计提交5c0f2901核对一致，不重开设计。原目录/分支feat/er-s16-catalog-outbox继续；原设计文件未改。
- 已实现可靠profile、V001/显式物理安装/同txid guard、原命令/开户身份预留/本地step、版本事件codec和真实Kafka relay。全部尚为未提交工作树改动；Controller/完整编排、初始化/基线、seed准入、审计恢复和独立JVM/真实停机仍待同一A继续。
- 最新受影响27项/2类PASS（物理26、profile1），33项未受影响的codec/旧分片HTTP/审核/鉴权证据复用，均零失败错误跳过；不称一次60项合并运行。原始XML、指纹和日志见round-25e0dd1e/{budget-retry-final-reports,final-reports,source-fingerprints.json,validation-current.json,catalog-budget-retry-3.log}；具体边界见[ER-S16A_IMPLEMENTATION_EVIDENCE](ER-S16A_IMPLEMENTATION_EVIDENCE.md)。这不是完整C16或A完成证据。
- 已实测并修正5.5.0 INSERT cast及SKIP LOCKED解析兼容；复用原生TypeHandler、显式路由的PG函数，未削弱锁/原子/断言。Mapper边界门禁670 Java/98 XML/45历史SQL通过；安装器原内联JDBC已移到专用物理Mapper/XML，未放宽检查脚本。
- 同Agent分离只读基础复审为PARTIAL、不可交付；事务预算/owner锁序已补并验证；后续仍须运行装配/日志脱敏、完整业务与故障验收。没有待确认业务问题，恢复继续同一A。
- 前置S15 CI38964531069精确686454dc已completed/success，8/8成功，preceding-ci-final.json；不再等待或重跑旧CI。11保护、37旧迁移fixture、59旧SQL、1373旧产品文件、22 S15源码和CODEX_PROGRESS历史正文保持。
- 本轮Maven任务均已结束，无结果未知操作、无新提交/合并/push/共享副作用。HEAD仍5c0f2901，main/origin-main仍686454dc；20个新源码/测试/资源文件须保留。恢复入口round-25e0dd1e/handoff.json。A未完成不进入B，S17～21继续待后续，S22～25暂停。

## 当前回合43880a89及父纠偏b48fcdef：ER-S16A（DONE / PASS）

- 四Controller可靠写、固定开户身份/原响应、完整快照、双库初始化/基线、真实Kafka、稳定查询/审计恢复、有限worker/relay已闭合。可靠profile SQL日志关闭，原profile和分片规则保持；广告主status/fanout明确拒绝至B。
- `catalog-full-gate-3`实际114项/14类PASS，9:29；原30312在客户端中断后继续，核对终态及进程退出再改源码。父seed兼容性/缺记录纠偏后`parent-corrections-1`26项PASS，未受影响项按指纹复用；不能合称一次140项。最终真实ROOT_CREATE权限恢复专项PASS，早前未变权限政策证据保留。
- 3个只读审查Agent及7项确认发现复验闭合；hygiene无阻断，仓库未配置formatter的限制已记录。11保护、37旧迁移fixture、59旧SQL、正式设计、历史正文、用户改动保持。
- 精确源码、运行/失败原证据和Git交付恢复入口：`.dev/codex-flow-reliability/round-43880a89/{checkpoint,final-source-fingerprints,separate-review,delivery}.json`。原分支/设计提交保留，按持续授权独立完整实施提交、正常ff/push并登记精确新CI；未有CI终态时不得声称全绿。
- 本片只完成A生产者恢复；B暂停fanout/批次、S17消费者/Redis及S18～21仍待后续。无共享启用、部署或清理。
