# EC05f 评估、调参与校准验证

DONE/PASS（本地），基线b8638f28f72608620df88228ffb901bc8b0881ae；按EC05F_DESIGN实现三种评估Job。父EC05余项与EC06～08仍未完成。

逐用户召回/排序异常汇总为JOB_EVALUATION_PARTIAL_FAILED，成功用户的既有部分CSV/曲线仍发布，但不再显示完整成功或完整调参建议。无测试正例、无可用结果、校准样本不足、策略不支持使用明确目录。真正空候选不混同依赖故障；一个召回channel有有效结果时允许其他channel为空。全缺pCTR/pCVR拒绝调参，每个用户各有部分可用head仍正常搜参并显示候选过滤warning。

Eval中断恢复线程标记、停止等待并shutdownNow，Error直接传播；不能撤销已经发生的模型调用。非法参数、空列表项、NaN/Infinity在副作用之前拒绝，bins=0不再覆盖旧曲线。非有限模型分数/head与融合溢出不可生成误导结果；校准先检查整用户结果再积累样本。提取真实共用的EvaluationFailures和每箱最少样本常量。SQL、schema、依赖、公式、抽样、CSV字段和正常发布协议保持。

目录新增8项，共202，原194项和8族逐项保持。实际模型调用用受控替身，数据库/Redis与子JVM为真实独占测试，不代表真实模型推理验收。

## 验证

- 后端41项/5类，0失败、错误、跳过：EvaluationJobsPostgresTest 13、JobArguments 3、JobRunner 5、DAG 6、JobStatusRecord 14。
- 原正常CSV/曲线SHA：Eval `33d46a22…`、Tune `b867ae79…`、校准 `b07af253…`；原依赖故障部分产物SHA：Eval `c3d24ad2…`、Tune `a3a924cf…`、校准 `4caf4c21…`。均按既有完整64位摘要断言保持，只改故障时最终状态与完成提示。来源副本、取整/历史过滤、抽样、连接释放断言保持。
- 全空输入/历史重复、合法空候选、全依赖失败优先、全缺/部分head、无效参数不发布、bins=0旧曲线保护、NaN分数/head、有限值乘法溢出、Fatal Error传播均通过。
- 真实子JVM的工作线程阻塞后触发调用线程中断：JOB_INTERRUPTED、调用线程中断标记为true、工作线程被停止、仅保留CSV表头且无成功摘要；随后正常运行恢复原完整摘要。
- 首轮只因测试fixture误用不存在的RecallContext.channels()而testCompile失败，修正为既有enabledChannels()；最终专项全部通过，未改变行为断言。
- 前端错误展示/Ops 57项；生成TS的Prettier/ESLint、Python目录7项、生成物check、605Java/90XML/45历史SQL守卫、24模块compile和diff检查通过。Hygiene无finding；Java统一formatter和独立静态分析未配置，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。没有新增可见页面，本片无需视觉布局验收。

## 命令与证据

```sh
mvn -B -pl recsys-offline -am test -Dtest=EvaluationJobsPostgresTest,JobArgumentsTest,JobRunnerErrorTest,DagJobRedisTest,JobStatusRecordTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console
npx --no-install vitest run src/api/__tests__/errors.test.ts src/pages/project/__tests__/OpsConsoleErrors.test.tsx
npx --no-install prettier --check src/api/generated/errorCodes.ts
npx --no-install eslint src/api/generated/errorCodes.ts
```

9源清单/tmp/recsys-ec05f-source-paths.json，排序路径NUL内容NUL SHA256：3c23d14eed4f8abf5a73ea992ec91c848bd9b65d782592a91f7cfc08f7274a61。最终专项启动后源码未变。

- `/tmp/recsys-ec05f-targeted.log`：`a4ad1ed63d04b967509a0772b5513659104718d48594289dcbb64dd5f02cea4d`
- `/tmp/recsys-ec05f-targeted-final.log`：`5558151cbd347105b7c3c1054f246465d13825156747435f055fb454c2a65da2`
- `/tmp/recsys-ec05f-compile.log`：`b9e02a7a4f9d3fea58c891d869497b1b8e93dc61d766658ccbe9905d02ba928c`
- `/tmp/recsys-ec05f-frontend.log`：`ca128f408a59dd515e04ce84ea1aa11aa18f2a740f6af5f64886e3d38d55930a`
- `/tmp/recsys-ec05f-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`

正常任务commit/ff/push已授权，13交付文件按显式清单；保护原8文件和暂停后缀，保留所有运行数据/测试输出，无清理/生产部署。回退会恢复旧假成功且不能撤销已发布部分结果。基线E CI38812115409四门SUCCESS、build仍运行；最终CI仍须EC08在最终SHA核验。
