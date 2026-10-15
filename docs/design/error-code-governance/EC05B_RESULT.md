# EC05b 类目加载和部分批处理失败

DONE/PASS（本地），基线7bd314fdd1c243d4fa0a9e3260f75169642fc69c。父EC05其余作业、EC06～08继续。

Flink CategoryEnricher仅完整加载后发布快照，加载失败以STREAM_CATEGORY_LOAD_FAILED使open失败，失败重开清掉旧快照；正常空表/NULL/重复行/大ID/序列化保持。底层ResultHandler API未改，不再让其半份构建Map成为已发布快照；原Flink重启策略不变。

向量回填保留逐项继续和成功写入、skip-existing；配额或任何失败最终抛专用码，DAG下游停止。null/维度/非有限向量明确拒绝。InterruptedException恢复取消标记并立即终止；JobFailures也保留中断目录码。报表发布仍自举后检查目录、逐文件继续、名称幂等，缺目录/目录读取失败安全码，部分文件失败最终失败，成功文件保留。错误cause仅内部诊断，不公开任意异常说明。

新增7码，总166，原159/8码族保持。较长的中英混合恢复提示触发现有Prettier100列限制，生成器按已有规则折行，中文全角按两列；补标准库单测，未手改生成物或新增formatter。Java生成内容未受折行修改影响。

## 验证

- 最终43项/6类（common14、offline23、streaming6），0失败错误跳过。真实pgvector验证约束拒绝后的部分效果、配额停止后续项、修复并skip-existing续跑，原事务/数据源/NULL/连接释放保持；受控EmbeddingClient抛中断验证标记恢复与此前真实落库，未宣称调用真实模型或进程取消演练。
- 真实PG报表验证坏CSV/目录/约束拒绝仍继续后续文件，最终部分失败可见，修复后补齐、原已发布文件不覆盖。输入目录缺失仍保持原建表注释/索引。Flink真实PG缺表失败与恢复；ResultHandler内部未发布Map仍可保留子集（兼容内部API）。
- streaming verify又跑6项SQL/算子测试+1项最终fat jar子JVM集成：生产类来自jar、Mapper XML/驱动SPI可用，无Spring ApplicationContext仍完成真实PG查询。不是Flink集群部署或重启演练。
- 全仓24模块compile；Python7项及生成check，603Java/90XML/45历史SQL守卫和diff通过。前端仅目录生成变更，57项错误解析/Ops组件测试及生成TS Prettier/ESLint过；页面产品代码未改，本片不重复宣称全前端168项。首次格式检查失败经生成器修复后最终通过。
- Hygiene无阻断，3个catch Exception advisory：向量逐项/报表逐文件汇总并最终抛出、Flink启动边界归一并重抛，均不是静默成功。Javaformatter/独立静态分析未配置；IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。无SQL/schema/依赖/新重试，保留成功部分不表示原子全批事务。

## 修订与命令

13源清单/tmp/recsys-ec05b-source-paths.json，排序路径NUL内容NUL SHA256：1a8f6534e5f556ac56df514bb6eb23e4442e955703f89d3e2c4df0d096a265e0。Java最后修订后重跑43项/verify/compile；生成器后续修订仅TS折行，Python与TS检查另在最终版本验证。

```sh
mvn -B -pl recsys-offline,recsys-streaming -am test '-Dtest=BackfillEmbeddingPostgresTest,ReportPublicationPostgresTest,CategoryEnricherPostgresTest,JobRunnerErrorTest,DagJobRedisTest,JobStatusRecordTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-streaming -am -Dtest=CategoryEnricherPostgresTest -Dsurefire.failIfNoSpecifiedTests=false verify
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

- `/tmp/recsys-ec05b-test.log`：`782f557233973e59403947defb0778cb01d0db5752666c86ab47e613b88195d4`
- `/tmp/recsys-ec05b-test-final.log`：`c28191f248653b8d58de87630188ae0df6b62f1390465f5d6391248422f4d1bd`
- `/tmp/recsys-ec05b-artifact.log`：`c95b7497bf78abf0b6fcd72c9e98b9e5f972093f873ed0ca7cc7b607a4a43e94`
- `/tmp/recsys-ec05b-compile.log`：`a510c7233c3ed7c18a3694e3cfe73d4385b9cb0e0f0e907eed5e36ccf0bfd1ab`
- `/tmp/recsys-ec05b-frontend.log`：`7f37ed2c231a2de56c8bc63ee9f991f5239d62604c9d15e315c34568869e3673`
- `/tmp/recsys-ec05b-frontend-final.log`：`b5b97dbeb18675e8829aeae0514b2bc041ac0f55f57df0e991a805346d3c60f2`
- `/tmp/recsys-ec05b-hygiene-final.json`：`28db8f45c14ae4d6c1b94788e4b9ff32e7fa39da2e23d1438f7e4ffd3b4bbc6d`

17文件正常任务commit/ff/push授权，保护8个用户文件。回退恢复旧假成功，不撤销已写入结果。原目录唯一worktree，忽略数据/模型/env/构建产物及测试输出保留，无清理授权。基线A CI38807812407安全/自然成功，其他三门仍运行，非最终全绿。继续EC05其余输入/校验/降级出口，之后EC06计算和发布债务、EC07/08。
