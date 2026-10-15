# MP15：全量迁移验收与结构终审

本地DONE/PASS；Git交付和当前提交的远程CI按PROGRESS_STATE/根交接记录。生产实现为`bc9459036cb963ee4212358174799bfaf4e0908a`（MP14），验证后未改生产Java/POM/XML。MP15新增CI守卫3源指纹`a3bb3055aeeae3f13a5da496b1b5ba66d4d98d22a6fafb12cd1c5ed3bf6fd099`，清单`/tmp/recsys-mp15-source-paths.json`。

## 验证结果

| 检查 | 实际结果 |
|---|---|
| `mvn -B -e clean verify` | 24 reactor SUCCESS；1049项/202类，0失败/错误，4按需skip；含最终Flink shade真实PG查询与JaCoCo原契约门槛 |
| money-chain原命令 | 384项/68类，0失败/错误/跳过 |
| natural-exposure原命令 | 75项/13类，0失败/错误/跳过 |
| `CI=true` PostgresIntegrationGateTest | 1项通过；补跑全仓中的环境条件skip，Docker实际可用 |
| 广告浏览器 | Chromium 4项通过；后端fixture 1项通过，真实PG账本、Outbox/Kafka投影及最终对账 |
| 自然浏览器 | Chromium 5项通过；后端fixture 1项通过，真实PG唯一事实/原子Outbox；本套未执行Kafka投递 |
| 混排浏览器 | Chromium 5项通过；后端fixture 1项通过，真实PG最终位置/预占/捕获/自然事实/Outbox；本套未执行Kafka投递 |
| 前端 | `npm run typecheck`、`test:run`（98项/19类）、`build`成功；既有React act/Router future flag提示不作为失败隐藏 |
| ONNX / 跨语言契约 | 10模型自包含；Python侧10 golden编码逐位一致 |
| 持久边界守卫 | 591生产Java、90 Mapper XML、45历史SQL摘要全部通过；6个回归场景测试与Python编译通过 |
| Hygiene | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻塞；统一formatter/独立Java静态分析未配置 |

4个全仓skip均已通过显式命令覆盖，未将Docker不可用的跳过视为成功。上述命令严格串行执行本地Maven（浏览器脚本内Maven亦串行），仅使用独占测试组件，未改共享库。重叠专项测试不与全仓数机械相加。

全仓日志`/tmp/recsys-mp15-verify.log` SHA256 `bc84a893bbde551737c443d5f916cb9b8ae82c96c677f781596630dcad731764`；逐suite摘要`verify-result.json`。专项/浏览器命令、退出码和日志摘要见`/tmp/recsys-mp15-gates.json`；对应`money/natural/ci-docker/browser-ad/browser-natural/browser-feed.log`。三宿主日志已复制到`/tmp/recsys-mp15-{sandbox,natural,feed}-browser-backend.log`；前端记录`frontend-result.json`、ONNX/contracts/hygiene-final同前缀。持久证据为本结果与已提交测试，临时日志失效不能补造结果。

## 结构与SQL核销

详见[STRUCTURE_REVIEW](STRUCTURE_REVIEW.md)。业务SQL全部迁入具名Mapper/XML，普通权威目录CRUD实际使用BaseMapper；来源枚举替代字符串表名，四源装配保留原资源身份。无需重新拆分服务或批量增加透传接口。原45 SQL未改；运行时DDL已离开Java，bootstrap与报表初始化Mapper各保留原触发/失败边界。

自动守卫接入现有GitHub Actions build，阻止生产Java SQL/JDBC回流、XML原文插值、RETURNING副作用声明丢失及历史脚本摘要漂移。词法检查不替代真实PG/分片/事务验证。R9无SQL连接适配、类型处理及测试独立JDBC为有证据的例外。

README、项目总览/架构/能力/分片文档已同步；新增[持久层与Mapper规范](../../13-持久层与Mapper规范.md)。只提交当前任务显式路径；原8保护文件、console/test-results及忽略env/数据/模型/venv/构建产物保留，唯一worktree未变，无清理或部署。

## 后续目标

本片只完成SQL迁移的本地最终验收。最新远程CI必须按本次push的SHA核验；前序bd996b36的38782312351五项SUCCESS不能替代本次结果。随后继续用户追加的统一错误码、提示展示和相关魔法值治理。历史假成功/部分发布/大ID问题见PROJECT_REFACTORING_REPORT，整体goal仍active。
