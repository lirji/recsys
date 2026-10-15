# EC05a 作业失败传播与运维状态

DONE/PASS（本地），基线d3f15c62fd847e67c46d0f4a0a391e635d903b1f。对应父EC04剩余SystemOps及EC05首片；EC05业务Job/streaming和EC06～08未完成。

## 行为

共享JobState/JobStatusRecord显式编码，沿用status@时间[:code]。旧成功/失败别名保持，旧自由detail与raw不再公开，坏状态/日期为未知。SystemOps返回受控对象码、详情与重建raw；Redis不可读不伪装无配置/无记录。页面只选择本地目录、固定状态标签与合法时间，兼容旧响应也不回显错误原文。目录159（原149和8码族保持）。

JobRunner预检全部作业名称再执行；子作业异常归一化并传播，不打印完成。DagJob验证参数/目标/dry-run，子作业失败停止下游并持久化安全fail/skipped状态，然后向上传播。Redis缺失/取锁失败在执行前拒绝，已有锁为JOB_ALREADY_RUNNING；状态写入无法确认则停止下游，明确业务效果可能已生效，不重试或宣称回滚。锁释放失败只发独立JOB_LOCK_RELEASE_FAILED警告，文案明确执行已结束，不能误用“未开始”提示。

CHANGED：DAG不再支持无Redis/取锁异常时无锁运行；状态发布失败也不再视为完整成功。没有改SQL/schema/业务作业算法/新依赖/重试。业务与状态不原子；原6小时锁过期、无续租、旧持有者释放风险仍存在，本片不宣称解决长作业互斥。单个裸job不新增状态持久化，页面说明已纠正为run-dag。

## 验证与审查

- 最终147项/16类：common14、公共HTTP28、offline14、console91，0失败错误跳过。codec覆盖旧/新/坏记录；JobRunner预检、失败归一；真实独占Redis6项DAG与3项SystemOps，覆盖故障/下游停止/锁占用/缺依赖/超时、业务已执行但状态未知、恢复、安全HTTP；既有真实PG/Redis/观测HTTP全console专项复跑。
- 第一轮旧SystemOps测试有2个断言仍要求公开自由详情/失败无说明，按已冻结安全契约调整；Dag4项先通过。新33项通过后扩为最终147项，未放松新安全/失败断言。
- 前端168项/25文件、tsc+Vite build、生成TS与新测试Prettier、相关TS/TSX ESLint通过。4项新组件测试（API替身）验证未知与真空、历史状态安全码、当前不确定结果、坏更新时间、成功旧响应。旧更新时间校验补充后重跑最终前端验证。
- 全仓24compile，Python6项、生成check、603Java/90XML/45历史SQL、diff通过。最终hygiene无阻断；2项catch Exception advisory用于OfflineJob.run声明的受检异常在分发边界归一并重新抛出，包含中断标记恢复，不吞异常；1项枚举相似来自忽略的第三方PyTorch环境，判不适用。统一Javaformatter/独立静态分析未配置，结果IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。
- 全部Maven串行，仅独占容器。真实Redis暂停后的命令可能晚到，测试不把超时推断为无写入；状态记录可能与业务效果不同步。最终EC08仍需全仓/资金/自然/三浏览器/最终SHA CI。

## 修订和命令

19源清单/tmp/recsys-ec05a-source-paths.json，排序路径NUL内容NUL SHA256：8bc77a1c972a354be306b5d837a9fab956e61695370a992fe3f71136e2cc06d0。源码验证后未改；前端最终校验后无修改。

```sh
mvn -B -pl recsys-offline,recsys-console -am test '-Dtest=JobStatusRecordTest,JobRunnerErrorTest,DagJobRedisTest,DagTest,SystemOpsRedisTest,SystemOpsServiceTest,SystemControllerTest,SystemOverviewServiceTest,ConsoleReadPostgresTest,SystemObservationHttpTest,DiagnosisErrorTest,AlertsErrorTest,GlobalExceptionHandlerTest,UserProfileFailureTest,UserRealtimeRedisTest,ItemReadPostgresTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console
npm run test:run
npm run build
npx --no-install prettier --check src/api/generated/errorCodes.ts src/pages/project/__tests__/OpsConsoleErrors.test.tsx
npx --no-install eslint src/api/generated/errorCodes.ts src/api/types.ts src/pages/project/OpsConsole.tsx src/pages/project/__tests__/OpsConsoleErrors.test.tsx
```

日志SHA256：

- `/tmp/recsys-ec05a-existing.log`：`38486f16555a44ff6c784d459b32703cac250657414d5a854a80d3aab1b5e05f`
- `/tmp/recsys-ec05a-test.log`：`eface24b33057d6e927ec5d93f5b6e164250ad0a95ae7c541c80d7c0c2b72eeb`
- `/tmp/recsys-ec05a-test-final.log`：`ffa5a0c0b6a6de4e2404b402cbcdd127f9ebe602f2820a19e2144ce37571e3e6`
- `/tmp/recsys-ec05a-compile.log`：`35526a11659fbcc32a1c5954c2eabff4a4e45432dcbe8e6a9d1dd330d7298404`
- `/tmp/recsys-ec05a-frontend.log`：`7cbdab271ac29e940ad27bbc85bc52cc48d3e4c3946f36e5c9d0f2bd41eb8cb2`
- `/tmp/recsys-ec05a-frontend-final.log`：`546356c018ba5874683adfec01bbe9ba4e39b827beadc8b8a41521e3f2e87938`
- `/tmp/recsys-ec05a-hygiene-final.json`：`ade615536c9ce5b22bfece56b8d747e73062106b06ea6eb1a2766da6d9a72d37`

显式清单正常任务commit/ff/push，保护8个用户文件与暂停交接。无数据库回退，但代码回退会恢复旧假成功和历史错误原文展示。EC04 a～e5加本片完成既定公开边界本地验收；最终残留审计在EC08，不代替EC05内部Job/streaming及EC06。基线E5 CI38806780972尚运行，E3/E4 CI取消，最终不得引用取消运行冒充通过。
