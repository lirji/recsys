# ER-S02A：CPM 精确报价验收

日期 2026-10-15；基线 `f05eb60b`；分支 `feat/exact-billing-quote`；契约 [C02A](CONTRACTS.md#c02a首版-sandbox-cpm-精确报价er-s02a)。首版 CPM/GSP 范围 DONE / PASS。尚未接通预占、资格签发与回执收费。

## 实现范围

BiddingService 新增显式 `auctionSandboxCpm`，只选择 CPM 并拒绝 VCG/Listwise。旧 auction、旧 ad.v1、旧收费仍保持原语义。新路径在 GSP 计算前将有限输入桥接为十进制：出价与 pacing 分别转换后乘法，cap/floor/increment 六位规范化；阈值和因子保留源十进制，采用有理数比较边界并在输出一次 HALF_UP。展示可转 double，后续账务必须引用精确报价，不能读 SponsoredAd.chargedPrice 收费。

计价版本 CPM_GSP_CNY6_V1，CNY / PER_IMPRESSION，沿用已有每单次曝光报价，不做隐式千次换算。旧目录 double 已丢失的输入精度不能恢复，这一限制显式保留。

新增 V002 与 `ad_cpm_quote`，所有列/表有中文注释；V001 未变。服务端保存 quoteId、delivery/slot、广告归属、NUMERIC 单价、定价依据、摘要和 DB 时钟的五分钟期限。唯一键支持重试和并发；重复只读原快照，过期不续期。同 ID 改负载或同交付位置换 ID 冲突。新 protobuf 快照传输精确文本及微秒期限；未知单位/版本、改金额或延长期限均拒绝。

## 验证

```sh
mvn -B -pl recsys-ad-serving,recsys-advertiser -am test -Dgroups=money-chain -DfailIfNoTests=false
mvn -B -pl recsys-ad -am test -Dtest=BiddingServiceUpliftTest,ListwiseExternalityTest,AuctionGameTest -Dsurefire.failIfNoSpecifiedTests=false
```

钱链路 57 tests（本片新增 14），0 failure/error/skipped；另执行旧 uplift/listwise/auction game 定向回归。最后强化竞价半微元和 proto 期限断言后，重跑 SandboxCpmBiddingTest / CpmQuoteProtoMapperTest 共 6 项通过；未修改产品代码。日志分别为 `/tmp/recsys-er-s02a-final.log`、`/tmp/recsys-er-s02a-auction-regression.log`、`/tmp/recsys-er-s02a-boundary-final.log`。

| 验收 | 实际方法与结论 |
|---|---|
| 最终量化 | CpmPriceTest：半微元、略低于半微元、1/3、2/3、精确夹取、极大/极小比值、金额上限与非有限拒绝；PASS |
| 真实竞价边界 | SandboxCpmBiddingTest：次位 0.0000005、零底价输出 0.000001，冻结阈值仍精确；十进制 cap、非 CPM 过滤、零因子/低上限剔除、未支持算法拒绝；PASS |
| 兼容旧价格 | 新旧入口独立验证，旧报价 0.0000005 保持未量化；现有 GSP/VCG、uplift/listwise 回归通过；PASS |
| PG 冻结与期限 | CpmQuotePostgresTest 5 项：12 并发同键返回同一快照，金额精确、期限恰好五分钟，重建 Repository 保持原行；构造已过期历史行后重试不续期；PASS |
| 唯一性与约束 | 改金额/广告主冲突，同 delivery/slot 换 ID 冲突；数据库拒绝 NaN 和单独延长到期时间，表/列注释齐备；PASS |
| 迁移与装配 | 真实 PG 顺序应用 V001/V002；旧预算验证仍通过；新配置默认无 DB 依赖，V002 摘要漂移拒绝启动；PASS |
| proto | 真实序列化往返保持 Long ID、六位金额、依据与微秒时间，未知版本/单位、改金额/期限拒绝；PASS |
| 检查与保护 | git diff --check、文档链接/围栏/DAG、六个用户文件哈希、V001 与基线无差异；PASS |
| Code Hygiene | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，唯一限制为根 Maven 未配置 formatter；无 blocking finding。静态分析未配置，不声称执行了不存在的检查 |

初次新测试使用了不存在的枚举名 KW，编译检查发现后改为仓库实际 KW_EXACT；没有调整业务断言。PG 测试仅使用隔离 Testcontainers，没有操作共享库。没有新增中间件、依赖版本或 UI。

## 审查、恢复与后续

独立验证 pass 核对：报价写入是一条原子 INSERT，冲突后只能查询，应用路径没有 UPDATE/续期；价格不从旧 chargedPrice 构造；回读验证持久金额与冻结依据一致。新配置仍默认关闭，缺迁移拒绝启用。新 gRPC 数据类型尚未提供客户端提交价格的入口，公开预占只能传服务端 quoteId。整次交付的幂等恢复由 ER-S04 实现，本片单报价幂等不能替代整次操作恢复。

V002 操作与回退见 [runbook](SANDBOX_BUDGET_RUNBOOK.md)。保留 V001/V002 和报价历史，不以删表回退；尚无报价产生的资金副作用。未声称支持新 VCG/Listwise、CPC/CPA 结算，也未测生产容量和恢复目标。ER-S03 接下来实现服务端报价预占、硬上限、释放及到期回收；不得从终端接受金额或延长期限。
