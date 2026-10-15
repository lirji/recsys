# ER-S11A：CPC/OCPC 精确报价

2026-10-15；基线 `b0b194321c4f4124e77c311dceb3867066d9dad3`，分支 `feat/sandbox-click-settlement`；[C11 正式边界](C11_DESIGN.md)。**S11A DONE / PASS**，父 S11 仍 IN_PROGRESS；点击预占、接受和捕获尚未实现，不宣称点击已收费。

## 实现

点击价格显式 CPC/OCPC、PER_CLICK、CLICK_GSP_CNY6_V1。共用 GspPrice 精确有理数边界与六位 HALF_UP，CpmPrice 仍保留原五字段、单位和版本。实际BiddingService新入口限定点击模式及标准GSP，OCPC继承现有有效出价/pCVR/系数；未知模式或未支持算法拒绝，不降级成旧浮点报价。旧 auction 与 CPM 入口继续通过回归。

纯点击候选调用真实 AdPipeline 流程，保留 DCO 和精确依据，不写预算/旧曝光/去重/事件。V008 的独立 ad_click_quote 冻结原主体、大ID、最终位置、标题、模式、数学依据、金额和DB五分钟期限。quoteId及operation/slot唯一，更新/删除被数据库拒绝；到期候选不能首次创建，原结果仍可查询和恢复，不续期。默认关闭且只允许广告 owner 装配，没有新依赖/前端变化。

## 验证

| 验收 | 方法与结果 |
|---|---|
| 数学/单位、半微元、极小概率因子、极值与非有限拒绝 | ClickPriceTest、CpmPriceTest PASS；实际竞价 SandboxClickBiddingTest 4项 PASS，OCPC使用实际OcpcBidder纯计算，Redis系数读适配为确定fixture |
| 旧CPM与旧竞价兼容 | CpmPriceCompatibilityTest 保持恰好五字段JSON往返及原版本/单位；原SandboxCpmBiddingTest 5项及BiddingServiceBillFactorTest 4项PASS |
| 纯候选没有资金副作用 | SandboxCandidatePipelineTest 扩展点击入口，原price和ad保留；验证未触及旧pacing写、日志、GD和分配写入；候选故障传播 PASS |
| 真实PG并发和持久化 | ClickQuotePostgresTest 6项 PASS：12线程同ID只一行、重启、同位置改ID、价格/模式/用户/题名冲突、跨主体拒绝、过期候选/原结果、历史报价不续期、不可改删、字段/表注释、旧CPM writer共存；报价不创建预算账户/预占 |
| 开关与迁移 | 默认关闭、grpc engine不装配/owner可装配、缺数据源、V008摘要漂移拒绝；V008漂移不影响旧CPM schema验证。真实PG迁移完整V001～V008 |
| 选择集与完整钱链路 | 首轮26项PASS，`/tmp/recsys-s11a-tests.log`；最后追加V008漂移检查后完整 `mvn -B -e -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false` **170项、0失败/错误/跳过**，`/tmp/recsys-s11a-money.log`，含既有真实PG/Kafka、RPC/HTTP、硬预算/回收/CPM/混排 |
| Hygiene/格式/静态 | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，`/tmp/recsys-er-s11a-hygiene.json`。无根级canonical formatter，沿周边Java样式；本仓Java无独立静态分析配置，记SKIPPED而非假称通过。编译和测试实际PASS；五分钟与十位置为已命名契约常量，advisory复核无阻断 |

第一次选择集因公共领域模块没有Jackson而测试编译失败，已将序列化测试放到本就依赖Jackson的ad测试侧，未向领域模块加依赖。历史期限测试用隔离PG的仅本条INSERT时钟触发器生成过去行，随后移除测试触发器；未关闭或放宽产品不可变约束。未真实等待五分钟，不以该fixture证明生产时钟同步。

本片没有可见UI变化，浏览器视觉验收N/A；旧CpmPrice序列化及实际钱链路覆盖共享影响。未执行环境迁移、真实收费或部署。操作保留期限仍待治理，V008不清理历史。用户六个既有文件及本地产物保留；S15A仅草案，未作为本片代码交付。

## 运行和下一步

已授权的隔离adDb在V001～V007后显式执行 `recsys-ad/src/main/resources/db/ad-billing/V008__click_quote.sql`，设置会话 `recsys.migration_sha256` 为实际文件SHA256，明确连接并使用ON_ERROR_STOP；应用仅校验，不自动建表。`recsys.ad.billing.clicks-enabled` 默认false，还须sandbox-enabled且为owner。本片启用只装配报价repository，没有收费入口。

回退关闭点击能力即可停止新报价；保留V008和历史，不改变旧CPM运行。后续S11B加入资金义务后须重新定义在途恢复/回退，不能沿用“关全部bean”停止已有义务。继续冻结S11B并完成预占、曝光依赖、点击幂等/反作弊、原子账本Outbox，再接S11C双模式API与可靠事件。Q-03已获用户批准，不重复询问。

SKILL_HANDOFF: backend-implementation → implementation-validation → update-progress-docs；slice=ER-S11A，status=COMPLETED，gate=PASS；根级formatter/Java独立静态分析限制如上，不替代父S11业务闭环。

非文档变更按路径和SHA256排序汇总的验证指纹：`a11123e02c4bec4de9545b423238cc93452c2c045f4ff2e2056c45e34c7c4249`。文档本地链接/围栏、git diff --check与用户文件哈希检查PASS。

## 交付核对

`365595e261d6018a2a430ee16ed1c65372f37767` 已正常合并并推送 main；2026-10-15 核对 HEAD/main/origin/main 一致，当前复用 `feat/sandbox-click-settlement`。远程 [CI 38691727206](https://github.com/lirji/recsys/actions/runs/38691727206) completed/success。下一步 S11B 尚未编写代码；用户清空上下文前已保存根目录 CODEX_PROGRESS.md，恢复不重新执行 S11A。
