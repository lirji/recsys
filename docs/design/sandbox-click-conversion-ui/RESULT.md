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
- 实际查看 1440px CPA 弹窗和广告主面板；390px 自适应断言通过。截图曾提前于布局完成，最终脚本已调整为先等宽度再截图，最终视觉证据在02C补齐。
- 本机全仓 ESLint 0 error / 17 warning，16条原有，新增1条为独立角色context与hook同文件的 react-refresh 提示；不影响编译/运行。前端变更格式已检查。
- 测试宿主由固定创建时 deadline 改为既有每次 RPC deadline 拦截器，避免长浏览器套件错误超时；生产后端未改。失败快照与stdout增加证明/JWT脱敏，trace/HAR关闭，CI仅上传显式遮挡证明的截图。

上述浏览器内可控权限引擎验证守卫与恢复，不冒充真实授权。独立真实权限结果将在02C单列。
