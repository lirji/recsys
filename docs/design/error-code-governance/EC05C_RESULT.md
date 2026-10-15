# EC05c 导入输入和部分完成错误码

DONE/PASS（本地），基线f09ccef8d7cbccd4dd82c9120b1f6af7e822cbda；范围及行为变更见EC05C_DESIGN。父EC05其余Job与EC06～08继续。

五个导入作业的必需输入/参数/部分完成改为明确安全码，成功部分保留。SemanticId识别真实训练header，Tower无header及原维度表兼容；原空文件清除、500/1000批次、重复行、真实晚DB失败和事务边界保持。R9在发布前完成输入校验，require-all仅接受布尔字面量，全部无输入明确失败；原可选组发布和手动事务不变。ImportItems确实缺少可选ratings保留0热度并带目录warning；坏输入不发布半份计数，数字捕获仅包CSV解析，存储异常由JobRunner归为执行失败。

JobArguments收敛实际重复CLI解析，无新框架。新增9码共175项，原166项和8码族保持。无SQL/schema/依赖变化，无实际模型或下载调用。

## 验证

- 最终65项/10类（common14、offline51），0失败/错误/跳过。独占PG/pgvector验证原批次、数据源隔离、NULL、重复、空文件、事务、500项成功后真实约束失败；新增缺输入/坏参数无副作用、合法header、脏行部分效果与R9无发布验证。独占Redis的DAG原失败/停止下游覆盖保持。
- ImportItems使用真实临时文件和受控ContentService写接口：正确CSV/缺可选输入/坏输入/目录不可读/部分成功/存储故障分类共7项；不以此声称真实内容DB验证。
- 前端错误/Ops57项、生成TS Prettier/ESLint通过；Python7、生成check、604Java/90XML/45历史SQL守卫通过；全仓24模块compile成功。
- 首轮10处旧断言要求吞错成功或旧异常类型，按新契约更新精确码，同时保留数据效果断言；最终实际通过。最后仅删除一条与新行为冲突的重复Java注释，不影响可执行代码；hygiene/diff再核验。Hygiene无finding，Javaformatter/独立静态分析未配置，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。

## 证据

16源清单/tmp/recsys-ec05c-source-paths.json，排序路径NUL内容NUL SHA256：5a17456b6e9ba75f633d2d145e07a0fbe0392ee648ce6c365fbbe36e20edc395。

```sh
mvn -B -pl recsys-offline -am test '-Dtest=ImportBehaviorPostgresTest,ImportTowerPostgresTest,ImportSemanticIdPostgresTest,ImportR9EmbeddingsPostgresTest,ImportR9EmbeddingsJobTest,ImportItemsInputTest,JobArgumentsTest,JobRunnerErrorTest,DagJobRedisTest,JobStatusRecordTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console
npx --no-install vitest run src/api/__tests__/errors.test.ts src/pages/project/__tests__/OpsConsoleErrors.test.tsx
npx --no-install prettier --check src/api/generated/errorCodes.ts
npx --no-install eslint src/api/generated/errorCodes.ts
```

日志SHA256：

- `/tmp/recsys-ec05c-test-final.log`：`64104140f210eaafee0448a539351dd7d7fd1f9fe06addbee7622360a41ef919`
- `/tmp/recsys-ec05c-compile-final.log`：`200a8040858baba0d10306426cc5696f1168acbfc5913e7e860e6e5afcf62cf3`
- `/tmp/recsys-ec05c-frontend.log`：`5a1b3f58df8ad0d3ade5838a256c12495c98331216f896bc23c199501594691d`
- `/tmp/recsys-ec05c-hygiene-final.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`

20文件正常任务commit/ff/push授权，8保护文件不stage。原目录唯一worktree，数据/模型/env/构建产物和测试输出保留，无清理授权。回退仅恢复旧错误行为，不能撤销已经成功导入的数据。基线B CI38808644533四门SUCCESS、build仍运行；本片最终CI待实际SHA核验，不宣称五门全绿。
