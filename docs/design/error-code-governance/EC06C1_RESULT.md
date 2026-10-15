# EC06c1 巡检状态生产与后台消费验证

DONE/PASS（本地），基线70c1d0e91843fb3aa2b40a450bde13c5bdf3062c，按EC06C1_DESIGN实施。DataQualityViz仍需EC06c2修正可见状态，本片不宣称全部巡检展示完成；原其他四作业及EC07/08继续。

common纯契约DataQualityCheck共享三检查键和PASSED/BREACHED/NO_DATA/FAILED稳定码。CSV原metric/value列、指标与告警行保持，仅末尾追加三状态。缺表/非法统计为FAILED，其他检查仍继续；真空数据为NO_DATA，覆盖率分母0仍保留原1.0数值但不据此宣称健康。最终诊断CSV写出后，失败或无数据分别抛受控码；实际越阈值仍属于已完成诊断并固定码警告。参数严格解析，原days0/负数窗口、覆盖率>1和纯公式保持。

Diagnosis/Alerts共享消费必须得到三项有效状态，旧报表缺状态、未知值和NO_DATA显示完整性未知；FAILED明确检查失败。状态为BREACHED但breaches=0仍WARN。实际覆盖率/ECE/PSI及越阈值计数也须有效，PSI必须唯一；单有PASSED文本不能证明数据可用。自由文本仍不透传。旧报表可以查看，不能证明完整巡检；发布时消费者先行，本任务未部署。

目录3新增码，共226，旧223/8族保持。10源、14交付文件，无SQL/schema/依赖/公式/事务改变。

## 验证

- 71个不同后端用例/7类全部通过：首轮68，补充PSI消费校验后最终Diagnosis25替代22，Alerts7复验。DataQualityPostgres10、DataQuality纯公式6、JobArguments4、JobRunnerError5、JobStatusRecord14及上述消费者32。0失败错误跳过。
- 真实双schema PG及子JVM检查所有权、缺派生表/主事件表/类目列的独立失败、三项全失败、全空/缺近窗、阈值完成、NaN输入失败与补正恢复，连接释放通过。所有错误与缺数据场景无通过日志，状态和有效部分指标均明确。
- 原六份CSV去除新增三状态行后完整SHA不变；原PG分桶/负值与大于1预测值/distinct点击、窗口精确端点、0/负days、覆盖率>1保持。默认阈值三状态BREACHED、宽阈值三状态PASSED均真实验证。
- 后台HTTP验证FAILED/NO_DATA/未知/缺单项/旧报表/BREACHED+0数，均安全WARN；未知原始状态不出现在公开响应。告警共享同一失败信息，不多读eval；缺/非法/多个PSI数值不能假PASS。
- 前端目录57项、生成TS格式/ESLint、Python7/目录check、606Java90XML45历史SQL守卫、24模块compile、diff/hygiene通过。Hygiene无finding，Javaformatter/独立静态分析未配置，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。

```sh
mvn -B -pl recsys-offline,recsys-console -am test -Dtest=DataQualityPostgresTest,DataQualityTest,DiagnosisErrorTest,AlertsErrorTest,JobRunnerErrorTest,JobArgumentsTest,JobStatusRecordTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-console -am test -Dtest=DiagnosisErrorTest,AlertsErrorTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console
npx --no-install vitest run src/api/__tests__/errors.test.ts src/pages/project/__tests__/OpsConsoleErrors.test.tsx
npx --no-install prettier --check src/api/generated/errorCodes.ts
npx --no-install eslint src/api/generated/errorCodes.ts
```

10源排序路径NUL内容NUL SHA256 `e3af402151f0a4a4b5ee58d448fb3f0cd7a60344df44141f178e013622427bf7`；/tmp/recsys-ec06c1-source-paths.json，验证后源码不变。14路径显式交付清单/tmp/recsys-ec06c1-delivery-paths.json。

- `/tmp/recsys-ec06c1-test.log`：`9e5749dc71a69f98d5efb4ce00b7133eba2b9393f30843aebe7d0fa11f71bc7d`
- `/tmp/recsys-ec06c1-consumer-final.log`：`6e632dd6cfd7d8b87a9e244be2761640c75a65e9f1b656b42f0742e0c6f1df4c`
- `/tmp/recsys-ec06c1-compile.log`：`f64a133bcd40a8f83d1b36211a4ba747fba0730f739baabe28978a2c1f74fdf1`
- `/tmp/recsys-ec06c1-frontend.log`：`4427b42a304d214ff4418e2da090cedbc3c378def321c34a23418f94f9e0a5ed`
- `/tmp/recsys-ec06c1-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`

正常Git交付持续授权，8保护文件/暂停后缀/唯一worktree保持；不清理忽略数据或测试产物。回退会恢复巡检假通过，不能撤销已生成CSV。基线B70c1d0e9 CI38816941139最近pending，不称全绿。后续c2采用同一CSV和错误目录完成可见状态/截图/浏览器；最终EC08核验最终SHA五门。
