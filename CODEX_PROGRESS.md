# Codex Progress

## 任务目标

连续完成docs/design/enterprise-reliability/PLAN.md原企业可靠性DAG S01～S25，逐片实现、验证并按用户持续授权正常提交/合并/push origin/main。原总体计划与五类前端规划已批准，不重开全部规划、不完成一片就停；仅sandbox，不启用生产收费或生产部署。

## 已完成

- S01～S10已交付，细节见各ER-Sxx_RESULT；S10=b0b19432。
- S11完整CPC闭环：A365595e2、Bb2c2911a、C1e599b3e5、C2f914f571；旧浏览器fixture名称修复6904889c，继承CI38699749896全部SUCCESS，原失败已关闭。
- S12完整CPA闭环已DONE/PASS：A2f5c3dab、B2d107e28、C1f23ca416；三批CI分别38699749896、38701260200、38702085570全部五门禁SUCCESS。
- S12C2本地完成：严格广告主edit/fullyConsistent、独立engine/advertiser caller、稳定双模式交付、转化回调与原结果查询，原业务事件自身费用不混同资格费用。详见ER-S12C2_RESULT/ER-S12_RESULT。
- C2最终验证：355钱链路/67类0失败/错误/跳过；全仓其余328项/81类0失败/错误、4个既有按需fixture跳过；最终CPA专项45项0失败/错误/跳过；显式广告4+自然5+混排5真实浏览器全部PASS。最后三套脚本实际执行了默认跳过的三个浏览器宿主。

## 已修改文件

- 当前原目录唯一worktree，任务分支feat/sandbox-conversion-settlement。本批C2基线f23ca416368d2a80147858a33b79ad07937abda8；本地验证已完，接下来立即正常Git交付。
- C2共31个源码/测试/POM路径：`/tmp/recsys-s12c2-source-paths.json`；最终源指纹`12dc4ace6f0df1430185201e3af27f69220dbc777bfcbc2ebd93778dc33d30f3`（排序路径+NUL+内容+NUL的SHA256）。
- 主要路径：common-ad ConversionDelivery/ChargeMode；ad ConversionPreparation/V015和固定模式仓储；engine ConversionDelivery配置/HTTP/V005及gateway；proto conversion.v1/ConversionProtoMapper；ad-serving SandboxConversionGrpcService；advertiser严格Authz、GrpcSandboxConversionClient、SandboxConversionController及7类验证/fixture。
- ad-serving仅test scope新增advertiser test-support，沿既有maven-jar-plugin打到target/test-support；没有新版本或生产依赖。TECH_SELECTION已登记两条hygiene许可。
- 本片同步C12_DESIGN、BACKEND_ARCHITECTURE、TECH_SELECTION、SANDBOX_BUDGET_RUNBOOK、IMPLEMENTATION_SLICES、B/C1真实CI证据、C2/父S12_RESULT与本文件。

## 未完成

- S12C2尚待本批Git commit/正常merge/push和实际远程CI核验；不能将本地PASS说成已推送或远程通过。
- 原S13～S25全部继续。下一片S13账户预算日对账/未决恢复；已只读考察，但尚未正式冻结C13契约或实现产品代码。临时设计笔记`/tmp/recsys-c13-design-notes.md`可复用，不是正式批准契约。
- S15必须同时包含自然Outbox和旧BehaviorService可靠事件；用户未跟踪C15A_DESIGN只是备选草案，不替代原S15范围。

## 当前问题

- 无当前实现阻塞。C2hygiene初次发现两处magic字面量比较和既有打包插件未登记，已改用HttpStatus/BidType、登记同项目既有插件，最终专项重编译验证通过；没有放宽断言/跳过关键测试。
- Code Hygiene最终IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；统一formatter未配置，独立Java静态分析未配置，沿周边约定，不引入新工具。
- 必须保持7项用户文件指纹`/tmp/recsys-s11b-protected.json`：docker/docker-compose.yml、docs/08-本地运行.md、docs/09-权限接入auth-platform.md、docs/skills/19-工程化-容器编排与可观测.md、scripts/dev-local.sh、未跟踪PROGRESS.md、未跟踪docs/design/enterprise-reliability/C15A_DESIGN.md。最近检查全部未变。
- console/test-results和忽略的env/数据/模型/venv/构建产物保留，无清理授权，无额外worktree。

## 下一步建议

1. 核对源码指纹/保护文件与显式暂存清单，正常提交C2、ff合并本地main并push origin/main；用户持续授权涵盖正常Git联网，不再问确认，不强推。
2. 检查该提交CI，更新真实交付证据。同时在原目录建立/复用S13独立分支，按原F12细化正式C13契约与有限实施切片，不扩张为自动冲正。
3. S13完成后继续S14～S25直到原目标全部处理；独立后续工作不因尚未定的生产参数停止。

## 重要上下文

- 已批准：SANDBOX、共享硬预算、CNY六位HALF_UP、Asia/Shanghai原预占预算日；跨日不改日；预算不得低于held+charged。图文50%连续1秒、hidden重置；自然natural-text-v1=5000/1000/300000。
- Q-03 CPC和Q-04 CPA已批准统一签发起5分钟、资格最多一次收费、先点击/转化持久化等可见、窗口外/释放后非计费。Q-04本轮用户回答“按照你的建议来”，已落PLAN/CONTRACTS，不再询问。
- CPA资格purpose=conversion-qualification，quoteId=qualificationId；业务键(SANDBOX,advertiserId,eventId UUID)，occurredAt UTC至微秒。actor来自当前授权，原actor只审计不参与内容幂等摘要。
- ad资金事务保持quote→命令唯一键→原预算日账户，持齐锁后读DB时间，双条件与ledger/Outbox/结果一起提交。额外事件DUPLICATE_QUALIFICATION自身false/null；原WAITING可随资格捕获显示自身实收但原status不变。
- 新能力默认关闭；停新增资格用conversion-preparation-admission-enabled，停新条件用conversion-receipt-admission-enabled，保留owner查询/到期与事件排空。三族relay/projection独立开关。
- CPA RPC展示组只接受独立engine，业务组只接受独立advertiser；HTTP每次强制edit/fullyConsistent，disabled/shadow/ADMIN不能绕过，依赖失败503、deny403、匿名401。内部caller沿现有受控凭据，不宣称共享凭据泄露后的强隔离。
- C2真实HTTP采用MockMvc+真实Controller/Authz/Client，TCP与PG真实；权限SDK/候选/实验替身不证明真实SpiceDB或JWT签发。测试只能使用独占PG/Kafka，不改共享dev_infra。
- S13拟沿ad owner聚合三类账户日权威/投影；offline通过契约驱动。快照必须一致、零元仍对账笔数、缺依赖不转0；任务检查点/fence/重放审计，不能改账本或跨服务写库。细节先正式冻结，不直接把临时笔记当代码规格。

## 验证日志

`/tmp/recsys-s12c2-{money,nonmoney,targeted,browser-ad,browser-natural,browser-feed}.log`、`/tmp/recsys-er-s12c2-hygiene.json`。首次窄测试还有persistence/online-final/authz日志。最终专项在hygiene修正后执行，最终源指纹见上。

## 恢复 Prompt

读取CODEX_PROGRESS.md、IMPLEMENTATION_SLICES和原PLAN，从真实Git状态继续。S12完整本地验收已完成，先核对/完成S12C2本批Git交付与CI，然后执行原S13～S25。CPA五分钟/一次收费/先转化等待/迟到非计费已批准，不再询问。保护七项用户文件与本地数据，不重开已完成规划、不等用户反复输入继续，不启用真实收费或生产部署。
