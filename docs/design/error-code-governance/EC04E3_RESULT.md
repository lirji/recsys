# EC04e3 报表与告警错误治理

状态：DONE/PASS（本地验证）；基线858ca2304a02621f49e2bfa626466177a0d4b454。实施范围和有意行为变化见EC04E3_DESIGN；父EC04及EC05～08仍未完成。

## 行为与结构

ReportService用私有目录快照保留DB失败上下文。DB故障且无对应CSV时，目录/最新类别/单文件查询返回安全CONSOLE_REPORT_UNAVAILABLE(503)，不会假空或假404；其他类别的CSV不能证明当前类别不存在。真实可读CSV回退、DB优先、正常NULL单元格/排序/字段、400非法名/404确定缺失保持。CSV解析失败为CONSOLE_REPORT_READ_FAILED(500)；目录或元数据故障不伪造空/零。latest传播失败，诊断可显示WARN。总146目录项，原144/8族保持。

AlertsService改为投影DiagnosisService的三类信号快照，消除重复阈值与吞错；该快照不新增eval查询。诊断State、告警Level/Source显式code枚举，JSON仍为旧字符串；复用E2 ProbeStatus做健康状态判定。WARN/FAIL及缺失信号可见，旧自由breach文本不再直接展示；正常健康空列表仍空，被动模块不参与在线告警。排序ERROR→WARN→INFO，页面空文案只描述已取得的信号，不承诺全系统健康。

无SQL/schema/事务/依赖/配置变化，不写业务存储。CSV回退未承诺比不可读的DB更新或代表完整DB目录。正常诊断/告警结构与排序保持，失败/未知展示是有意改进；不将新增错误码等同父目标全部完成。

## 验证与审查

- 基线复用E2在当前基线修订的63项/6类证据；改造后旧范围63项仍通过。
- 最终101项/8类（console73+公共HTTP28），0失败/错误/跳过。新增真实PG表故障+无CSV/别类CSV/可用同名CSV、坏JSON/null行及恢复、CSV解析/目录/元数据故障、HTTP400/404/503/500且安全。原DB优先/NULL/顺序/可选Mapper与局部读降级保护保持。
- 告警使用真实Diagnosis聚合，信号源替身；覆盖三来源失败、缺失、恢复、自由文本隔离、被动目标、稳定级别/source JSON与排序，并验证不额外读取eval。不是生产故障演练或真实Prometheus验收；E2真实HTTP观测测试在同批复跑。
- 前端157项/22文件、tsc+Vite build、生成TS/新测试Prettier、相关TS/TSX ESLint通过。新增真实React组件渲染验证空文案、WARN错误码、HTTP失败安全回退；API是mock，不冒充浏览器联跑。
- 最终全仓24模块compile，Python6项/生成check、600Java/90XML/45SQL守卫、diff均过。
- 首轮编译/101项已过，但hygiene发现剩余健康状态和告警来源魔法字符串，已改共享ProbeStatus和Source枚举并完整复验。最终无阻断；两条相似枚举advisory指向被忽略的第三方PyTorch/Click虚拟环境，与Java告警语义无关，判不适用。Java统一formatter/独立静态分析未配置，未伪称执行；最终IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。
- 本片不改资金/自然/sandbox路径，不重跑三套浏览器或完整资金；最终EC08仍需最终完整回归/CI，历史门禁不冒充本片结果。

## 修订与命令

14源文件/tmp/recsys-ec04e3-source-paths.json，排序路径NUL内容NUL SHA256：188697d68ab64f8c6051d712b393639e1e28f6101d46facbd56b27579ce2ec6a。最后Java修订重新专项/全仓编译，前端内容未再改变。所有本地Maven串行，PG只用独占Testcontainers，文件故障仅JUnit临时目录。

```sh
mvn -B -pl recsys-console -am test '-Dtest=SystemControllerTest,SystemOverviewServiceTest,SystemOpsServiceTest,ConsoleReadPostgresTest,SystemObservationHttpTest,DiagnosisErrorTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-console -am test '-Dtest=SystemControllerTest,SystemOverviewServiceTest,SystemOpsServiceTest,ConsoleReadPostgresTest,SystemObservationHttpTest,DiagnosisErrorTest,AlertsErrorTest,GlobalExceptionHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console目录
npm run test:run
npm run build
npx --no-install prettier --check src/api/generated/errorCodes.ts src/pages/project/__tests__/AlertsErrors.test.tsx
npx --no-install eslint src/api/generated/errorCodes.ts src/pages/project/Alerts.tsx src/pages/project/__tests__/AlertsErrors.test.tsx
```

日志SHA256：

- `/tmp/recsys-ec04e3-existing.log`：`4e0680261ac2e26fa604651b3f63d5a31ad17099561a0a39aa20c34ad8374991`
- `/tmp/recsys-ec04e3-after.log`：`0e67e2ddcb167671260103e355cbb5fb4aea3337f992d8f8704e7db26bbbf6bb`
- `/tmp/recsys-ec04e3-after-final.log`：`3a579eca7c6bcc23b1757f405d41be854b27f82f3bc39ec0dd519555c3261976`
- `/tmp/recsys-ec04e3-compile.log`：`2731782239ac60552e3e372df997000bacbd79de744fa9d10f39963f9d51adf5`
- `/tmp/recsys-ec04e3-compile-final.log`：`70585d675e06c49dc49ea7c72c3d8379823ac5b9c590782f8611891982ca6c78`
- `/tmp/recsys-ec04e3-frontend.log`：`3525773ecfcadf9410e9b58bfe1a0d0075e725a95d989c11f3ecfcf70c2ef227`
- `/tmp/recsys-ec04e3-hygiene.json`：`4309fccec89215432d6db2dbac8c62d97a71241200f5b0567b6107d0ddf1b693`
- `/tmp/recsys-ec04e3-hygiene-final.json`：`d4ced13b6355f28eec8fcb7d3ed00739bbcad03b65c4c6746912b79c0003d398`

## 交付和后续

18文件显式清单，正常任务提交/ff/push main，保护8项用户文件和暂停交接。最新基线E2 CI38803835991四门SUCCESS、build仍运行，不能称五门全绿；新片按新SHA核验。无数据库回退；代码回退会恢复旧假空/假健康。EC04剩余User360主视图、Item、SystemOps/作业状态继续；EC05～08不得遗漏。
