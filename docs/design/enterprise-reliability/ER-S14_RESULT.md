# ER-S14：Sandbox账户日资金只读报表

2026-10-16，基线a3bfb3bb，分支feat/sandbox-reconciliation-report。当前片实现/验证PASS；Git交付另据实际提交记录。S13B/C基线最新CI 38853983215七门禁及console-ci 38853983179全部success。本片不部署、不启用共享CPC/CPA；S15～21继续，S22～25仍不在本轮授权范围。

源文件28项指纹`7de10dfcb219d279323dd66a13a01ff3ad0ff0fc1d37692e2966944db4c6faa1`（路径+逐文件SHA256的有序聚合），清单`/tmp/recsys-s14-sources.json`。实现范围与契约见C14_DESIGN/CONTRACTS，运行入口见SANDBOX_BUDGET_RUNBOOK S14。

## 结果

- 原广告主报表增加独立单预算日资金区域。三族账本/Outbox/投影与账户同快照；所有金额六位字符串，计数/版本/大ID也为字符串，UTC时间显式字符串。账户不存在与合法零额分开，资金一致与投影落后可同时成立。
- 每次HTTP严格view/fullyConsistent且核对当前主体，无归属ADMIN不能绕过；坏RPC/DTO与依赖错误不变成零账户或终端403。响应no-store，401/403清当前缓存，身份与epoch在发送前和响应后检查。
- 日期草稿只在提交时生效；URL保存预算日。刷新/错误/切范围隐藏旧资金，查询不绑定曝光/转化持久队列。旧ad_event全期估计与离线文件显示独立来源及水位缺口。
- 前端VITE_SANDBOX_RECONCILIATION_ENABLED与后端sandbox-enabled/sandbox-reconciliation-enabled全部默认false；缺任一后端开关不安装读取入口。沿现有Dockerfile传递构建参数，受保护Compose不改。

## 验收证据

| 接受条件 | 实际执行 | 结论 |
| --- | --- | --- |
| 共享三族资金、精准DTO、读取无资金副作用、空账户及投影追平 | `mvn -B -pl recsys-ad-serving -am test -Dtest=ReconciliationHttpPostgresTest,ReconciliationOnlinePostgresTest -Dsurefire.failIfNoSpecifiedTests=false` | 13项通过，0失败/错误/跳过；`/tmp/recsys-s14-http-final.log` |
| disabled/shadow/enforce强制view，匿名、无归属ADMIN、撤权/SDK缺失、坏响应和默认关闭 | 同上真实Controller/TCP/PG，SDK边界替身 | PASS；替身不冒充真实权限 |
| view-only而非edit；真实撤权/依赖故障 | `scripts/test-reconciliation-authz.py` | PASS；真实SDK→auth-platform-server→独占SpiceDB；view允许且edit拒绝，无归属403、撤权403、匿名401、停授权服务503、资金表全行不变；`/tmp/recsys-s14-real-authz.log` |
| 字符串契约、非法日期、身份/epoch、迟到响应、撤权清缓存与显式恢复 | Vitest新增8项 | PASS；最终`/tmp/recsys-s14-final-unit.log` |
| 受影响前端整体回归 | `npx vitest run` | 39文件246项PASS；`/tmp/recsys-s14-console-regression.log`，最终仅状态值常量化后重跑相关8项 |
| 真实浏览器查询/刷新、投影落后到追平、故障恢复、默认关闭零请求 | `RECONCILIATION_MODE=grpc/in-process python3 scripts/test-sandbox-reconciliation.py` | 每种4项，共8项PASS（含离线报表来源提示）；最终日志`/tmp/recsys-s14-browser-final.log` |
| 1440/390显示与键盘/溢出 | 同上截图及实际查看 | 金额未截断、查询按钮层级明确、窄屏折行/表内横滚；390页面scrollWidth=390；Enter提交和刷新真实执行 |
| 编译、格式、静态、错误目录和持久化边界 | `npm run build`，相关文件Prettier/ESLint，三个仓库治理脚本 | PASS；`/tmp/recsys-s14-build-final.log`、format-check/format-status及eslint-status日志；错误目录生成一致、public-errors及persistence-boundaries无回流 |
| Code Hygiene | 公共hygiene gate | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻断；`/tmp/recsys-er-s14-hygiene.json` |

浏览器在两种engine配置下都走advertiser→owner真实TCP，只读路径不经过engine，所以不冒称本片重测了engine两种资金写实现。页面壳层和旧事件报表用导航替身；所有新账户日响应均真实HTTP/RPC/PG，不以路由mock证明资金。三族数据由原owner结算用例写入同一schema，报价时间偏移仅隔离INSERT触发器；Kafka恢复证据由已交付C13承担，本片不新增发布逻辑。

真实授权使用既有源码763ca23e0f0cc521b5f605375ab999198cf001db构建jar及其recsys.zed；SpiceDB镜像sha256:aa96009a0477f8a759149823407d47ad16d1a74390bae3102b3b0b7143502764。仅临时loopback服务和自己创建的容器，未改共享关系或外部仓库。

截图位于`recsys-ad-serving/target/reconciliation-evidence-{grpc,in-process}/reconciliation-真实账户日读取、键盘查询刷新、投影追平、撤权恢复与默认关闭-enabled/report-{desktop,narrow}.png`；已查看最终grpc1440和in-process390；状态值常量化后重跑双配置浏览器8项、组件8项及构建。离线AdReportViz来源提示与原明细展开使用独立CSV契约替身，实际查看offline-source-desktop.png，不冒充真实离线聚合数据。截图作为构建证据保留，CI只上传PNG，不上传带token的fixture文件。

## 修复与限制

实现期暴露并已修复：fixture广告主参数错位、错误响应未强制JSON、Instant默认数字序列化、刷新图标污染按钮可访问名称、fixture误用qualification表名、匿名测试宿主返回HTML导致验收器无法解码、故意坏数字字面量触发lint、状态字面量未沿显式域常量；离线页面测试的宽泛api拦截误拦Vite源码路径，已限定为/api/请求且页面内容/明细原断言通过。均保留原语义断言并复验；没有把失败转为跳过。

仓库没有统一Java formatter及独立Java静态分析，本片保持周边风格，编译/治理边界检查PASS；console现有Prettier/ESLint实际执行PASS。测试脚本hygiene的7条数值提示均为已命名的隔离启动/请求/清理超时常量，不是产品业务参数。JSDOM既有伪元素样式告警不支持视觉结论，视觉由真实Chromium截图承担。

七项用户配置/文档指纹均未改变，console/test-results、忽略env/数据/构建产物保留；不清理、不stage。无新增worktree，唯一原目录。关闭新读取入口是回退方式，不删S13账本、任务、审计或在途义务。
