# EC05e 种子与模拟作业验证

DONE/PASS（本地），基线bc7818679593b88bfcfb33132c2788243fd0d725；范围与显式行为变化见EC05E_DESIGN。父EC05剩余评估/报表/投影/共享CLI和EC06～08继续。

SeedAds/SimRec/SimAd复用严格数字参数解析；参数或来源错误先于clear。零生成数量有意改为拒绝，不能再用零数量和clear隐式执行只清理。真实空目录仍在原清理时机之后报错，安全文案明确旧数据可能已清除。SeedAds保留逐表继续清理，但任何失败最终汇总并停止生成，null Redis结果也按未知失败处理；不再报告完整清理或完成生成。SimAd创意读故障在写事件前拒绝，真实空创意仍生成NULL归因并带目录warning，部分广告缺创意规则保持。

新增5码共194，原189及8族保持；无SQL/schema/依赖/随机算法/批次/事务改动。未真实运行seed/clear，只对独占组件测试。清理跨库/Redis仍不是全局事务，失败不撤销已执行效果。

## 验证

- 最终55项/7类，0失败错误跳过：common14、offline41。种子10、推荐模拟7、广告模拟10、JobArguments3、JobRunner5、DAG6。
- 真实PG外键使一个分片的bidword TRUNCATE被拒绝：部分旧词仍在，后续广告表/广告主/Redis被原规则清除，最终失败且未再生成，没有成功摘要；移除测试约束后重跑补齐。真实Redis ACL拒绝KEYS：DB已清除但倒排保留，最终失败，恢复权限后可重跑。
- 坏参数/非法来源+clear保留旧表/倒排；空目录+clear明确报错且旧广告/模拟数据确实已清除。创意缺表读取失败没有写任何事件；真空表成功写100曝光、NULL归因并带目录warning。
- 原种子四数据源/真实分片/Snowflake/pgvector/倒排与重复约束保持；模拟默认50,000曝光、11,285点击、3,342赞及事件摘要保持；真实晚失败14,847项已提交、外层事务回滚/提交与连接释放保持。没有把这些实际部分效果称原子事务。
- 首轮29项中的5处旧无输入成功/吞错断言按冻结契约更新，全部数据效果断言保留；最终55通过。前端错误/Ops57项、生成TS格式/ESLint、Python7、目录check、604Java/90XML/45历史SQL守卫、全仓24compile和diff通过。Hygiene无finding，Javaformatter/独立静态分析未配置，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。

## 命令与证据

```sh
mvn -B -pl recsys-offline -am test -Dtest=SeedAdsPostgresTest,SimRecEventsPostgresTest,SimAdEventsPostgresTest,JobRunnerErrorTest,JobArgumentsTest,DagJobRedisTest,JobStatusRecordTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console
npx --no-install vitest run src/api/__tests__/errors.test.ts src/pages/project/__tests__/OpsConsoleErrors.test.tsx
npx --no-install prettier --check src/api/generated/errorCodes.ts
npx --no-install eslint src/api/generated/errorCodes.ts
```

9源清单/tmp/recsys-ec05e-source-paths.json，排序路径NUL内容NUL SHA256：019fce0a656a8d668190f4126b0d8993a8e587fbeae255b8eb9280623588c1e2。测试启动后仅清理三个生产文件尾部空白，未变可执行代码；最终全仓编译/守卫/hygiene针对该版本。

- `/tmp/recsys-ec05e-old-expectations.log`：`c5416cb5451996eed28fb6de0275e41181286b0fb80ca3a2a8e2a3f15cacae55`
- `/tmp/recsys-ec05e-test.log`：`ae58ecd7ccd1d04c9c432740678dc7a690125e7ef6809d49631120aef447582c`
- `/tmp/recsys-ec05e-compile.log`：`016e569136752b5ae7c1c9e4a8a24c806309f5a022583a2e4d8ee855991e6405`
- `/tmp/recsys-ec05e-frontend.log`：`6f9bea44fbcf2a9bf0b0d1c174f1101e1d321f7df48ea7701652759ce2fa63c7`
- `/tmp/recsys-ec05e-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`

13文件正常任务commit/ff/push授权，8保护文件与暂停后缀保持。原目录唯一worktree，保留数据/模型/env/构建产物和测试输出，无清理授权。回退会恢复旧假成功，无法恢复已清除数据。基线D CI38811445623运行中；C38810074692已取消（取消前四门成功/build运行），最终CI须在EC08按最终SHA核验。
