# ADV-UI-02 验证与交付记录

## 当前状态

2026-10-16：02A CPC/OCPC DONE / PASS；02B CPA DONE / PASS；02C 最终回归、CI 与 Docker 执行中，真实授权已通过。当前不能宣称整个任务完成或共享环境已启用新能力。

## 02A 验证

验证对象为任务分支工作树，源文件集合（排除文档）的 SHA-256：`64cfd894f1a972f6b913dccdfc14de25e934cc3e0fc8d53ac31efbbc86f08959`。验收复核以 C11 和当前 PLAN 为准，不把设计审查替代实现验证。

| 检查 | 结果与证据 |
| --- | --- |
| TypeScript 编译 | PASS，`npm run typecheck`，退出 0 |
| 当前 API/队列单测 | PASS，2 文件 8 项，`sandboxActions.test.ts`、`actionReceiptQueue.test.ts` |
| 真实 gRPC 浏览器 | PASS，3 项，`/tmp/recsys-actions-grpc.log`；Java 宿主最终对账退出 0 |
| 真实 in-process 浏览器 | PASS，3 项，`/tmp/recsys-actions-inprocess.log`；Java 宿主最终对账退出 0 |
| 视觉与交互 | PASS，当前桌面/390px 截图已实际查看；原操作恢复、查询、历史键盘关闭、窄屏宽度、身份切换、下载脱敏均有 E2E 断言 |
| 相关 ESLint / Prettier | PASS，显式本切片路径；全仓已有 16 条 warning 留待最终汇总，首次新增测试精度 lint 错误已修正 |
| 错误目录/公开错误/持久化边界 | PASS，三个仓库检查脚本均退出 0 |
| Code Hygiene | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻断；没有统一 Java formatter/独立 Java 静态分析配置，前端格式与 ESLint 已实际执行。四项 Python 测试超时已命名常量的建议项保留，不涉及产品规则 |
| 用户文件/暂停交接 | PASS，7 文件与原暂停后缀指纹均未变；不纳入提交 |

测试假设：登录 JWT、候选模型与查询理解为测试适配，候选正文/报价来自隔离数据库；真实资金 HTTP/PG/gRPC/事务/账本/Outbox 并未替换。此次未验 Kafka 投影与生产登录，不借此扩大声明；02C 继续已有链路回归。首轮 UI 溢出、in-process 宿主装配失败与受控停止后的测试 Vite 残留均已定位，详见实施证据；只终止本轮自建测试进程。

## 待完成

02C 按批准顺序继续，全量回归、最终 Git/CI/Docker 证据在执行后追加。

## 02B 验证

CPA 搜索交付/可见/业务事件、广告主详情人工证明与原事件查询已接入真实 C12。精简业务结果与完整资格结算分别校验：历史 ACCEPTED_WAITING 可随后显示自身实收，DUPLICATE_QUALIFICATION 自身始终 false/null，即使资格已结算。原事件参数被冻结，已有 ID 不接受换证明或时间；确认后只查询。

- TypeScript、生产构建 PASS；前端全量 37 文件 / 238 项测试 PASS。新增 CPA 五项测试覆盖微秒时间、错误响应关联、丢 ACK 刷新、原载荷、403/503、共享容量与同 ID 冲突。
- in-process 与 gRPC 各五项新浏览器 PASS，真实隔离 PG/Mapper/事务/账本/Outbox 对账均退出 0；两个顺序、额外事件不收费、原内容409、ADVERTISER入口、撤权与依赖失败后恢复均有断言。日志 `/tmp/recsys-actions-narrow.log`、`/tmp/recsys-actions-grpc-final.log`。
- 实际查看 1440px CPA 弹窗和广告主面板；390px 自适应断言通过。截图曾提前于布局完成，最终脚本已调整为先等宽度再截图；02C最终390px截图已实际查看，无整体横向溢出。
- 本机全仓 ESLint 0 error / 17 warning，16条原有，新增1条为独立角色context与hook同文件的 react-refresh 提示；不影响编译/运行。前端变更格式已检查。
- 测试宿主由固定创建时 deadline 改为既有每次 RPC deadline 拦截器，避免长浏览器套件错误超时；生产后端未改。失败快照与stdout增加证明/JWT脱敏，trace/HAR关闭，CI仅上传显式遮挡证明的截图。

上述浏览器内可控权限引擎验证守卫与恢复，不冒充真实授权。独立真实权限结果将在02C单列。

## 02C 当前证据

- 真实授权 PASS：`scripts/test-click-conversion-authz.py`，真实 RemoteAuthzEngine → auth-platform-server → 独占 SpiceDB，验证 owner/editor 允许、无关系ADMIN拒绝、撤权后PUT/GET拒绝、停独占授权服务后503；真实PG资金对账通过。server源码 `763ca23e0f0cc521b5f605375ab999198cf001db`，SpiceDB本地镜像 `sha256:aa96009a0477f8a759149823407d47ad16d1a74390bae3102b3b0b7143502764`。未修改其他仓库或共享实例关系。
- 完整前端 typecheck、37文件/238单测、lint（0 error/17 warning）、build PASS；相邻功能/默认关闭浏览器16项 PASS。默认构建两项新入口隐藏且零新协议请求。
- 格式、错误目录生成、公开错误边界、607 Java/90 XML/45历史SQL边界 PASS。Hygiene 为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻断；统一Java formatter/独立静态分析未配置，Python已命名超时常量的7项建议保留。启动重试的末次异常类别和清理失败已显式记录。

- money-chain 最终 BUILD SUCCESS，426项/74类，0失败/错误/跳过，包含新gateway路径和角色边界；日志 `/tmp/recsys-actions-money.log`。

- 双开关启用的生产 Vite 构建 PASS，输出 `.dev/actions-enabled-dist/`；默认关闭构建也PASS。旧CPM浏览器4项和真实账本/Outbox/Kafka投影最终对账PASS，日志 `/tmp/recsys-actions-legacy-cpm.log`。

- 旧自然曝光5项、Feed5项浏览器和真实PG唯一事实/Outbox对账PASS；这些两套浏览器不单独证明Kafka投递，CPM及money-chain覆盖投影。日志 `/tmp/recsys-actions-legacy-{natural,feed}.log`。

- 最终授权脚本与gRPC截图验收均退出0（session34340），最后5项浏览器PASS，390px最终截图已实际查看；1440px弹窗与广告主详情亦已查看。证据 `recsys-ad-serving/target/actions-evidence-grpc/`，日志 `/tmp/recsys-actions-{real-authz-final,final-visual}.log`。
- 34个当前任务源码/配置文件指纹：`4a75a8a0fcc5915fffc9b970ba8cd7b73f1629d556848cd98e601aa26069186b`；本机清单 `/tmp/recsys-actions-verified-sources.json`。不含文档和用户脏文件，验证后逐文件检查未漂移。

## 验收映射

| 计划项 | 最终证据 |
| --- | --- |
| A01～A03 | 默认关闭E2E、双开关构建、API/队列单测、新交付丢ACK浏览器 |
| A04～A07、A12 | 双模式新浏览器、CPA恢复/费用单测、426项资金链和隔离PG总对账 |
| A08～A10 | identity/可见hook单测、新旧浏览器的身份切换、原生hidden、离屏与历史只读 |
| A11 | 真SDK/授权服务/SpiceDB、真实gateway角色测试、可控故障浏览器 |
| A13～A15 | 实际脱敏下载、日志票据扫描、1440/390截图检查、旧14项和相邻16项回归、边界检查 |
| A16 | Git/CI/Docker交付中，以下恢复入口续接，不提前声明PASS |

## 本机发布边界

目标仅既有 `desktop-linux/recsys` 的 `console`，入口9095、同源gateway9080，legacy认证；两项新构建flags=false。新资金/授权共享配置保持现状。基于通过精确CI的源码git archive构建、revision标签和不可变image ID启动；保留旧console镜像 `sha256:38789156e4fd13ef043930e0e9a9e6bb567c665292cc387241d346e7dc1aa439`。观察八后端健康、实际页面、旧精确ID/审核状态以及零新协议请求；失败停止交付并用本次生成的rollback.compose.json恢复console。无数据库迁移，镜像回退不撤销既有业务效果。

## 恢复检查点

02A bd2f4609、02B aa0967ee已独立提交；02C所有本地验证通过，待本批提交→正常合并/push main→精确CI→本机console部署。当前目录原仓库、分支feat/sandbox-click-conversion-ui，无新worktree。已结束的session96398（426项资金链+旧14项）及34340（最终真实授权/新截图）均退出0，不重复执行。

- CI/部署未执行，不得猜测已成功。准备脚本 `/tmp/recsys-actions-deploy.py` 和 `/tmp/recsys-actions-smoke.cjs`，分别有精确CI、镜像、其他服务不变和健康/UI检查；实际发布前核对HEAD和运行环境。
- 保护：`/tmp/recsys-advertiser-ui-protected.json` 中七个用户文件及 `console/test-results/` 不stage；CODEX_PROGRESS只改当前前缀、不stage。暂停后缀去前导换行SHA256 `09ca9b776a47ea4c4ae8dee26595390a4ec791ba1c757b6e2bd65378a7fe6862`。
- 当前运行版本d787dda0，旧镜像和材料 `.dev/deployments/adv-ui-01-d787dda06268/` 保留。隔离PASS不代表共享环境新功能启用。不得恢复S13B～S25。
