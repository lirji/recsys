# EC06a 广告校准与报表验证

DONE/PASS（本地），基线82a4a07a94165e836320c1f0efdf95724b89a7cc；按EC06A_DESIGN实施。EC06其余7个作业和EC07/08仍未完成。

校准参数在读取和发布前拒绝；样本不足、非有限输入、分箱计算溢出和六位舍入不能表示的值不再返回成功或覆盖旧曲线。提取最小样本与发布精度常量，正常等频分箱/PAVA/JSON不变。广告报表无曝光、显式since缺值明确失败；无效点击计数异常/null/负值不再伪零。目录新增4码，共216；旧212条和8族保持。

## 验证证据

- 56项后端/6类，0失败错误跳过：AdSignals14、ExperimentReport13、JobArguments4、JobRunnerError5、DagJobRedis6、JobStatusRecord14。真实独占PG与Redis、临时子JVM运行，所有连接释放检查通过。
- PG写入NaN、正负Infinity、正负1e20及1e308，分别覆盖非法输入、舍入饱和和求和溢出；均专用码、旧曲线原字节保持、无完成日志，数据修正后恢复原曲线。bins0/负数/裸值/坏值、model裸/空白不写；99样本明确不足。
- 正常20箱、2箱完整JSON、NULL预测过滤、distinct点击关联、两源及Redis已有故障边界保持。广告CSV两份及AB四份原SHA逐字节通过，晚分桶SQL失败仍保留先前CSV行，未改变原目录时机。
- 计数异常/null/负值通过仅替换count方法的测试适配器验证安全边界；其他查询真实PG。该适配器不证明数据库网络故障。计数未知时子进程失败，无CSV、无完成或误导零摘要，原建好的空目录可保留。恢复正常及真实0计数报表保持完整SHA。
- 前端57项、生成TS的Prettier/ESLint、Python7/生成物check、605Java90XML45历史SQL守卫、24模块compile、diff/hygiene通过。
- Hygiene无finding，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：Java统一formatter与独立静态分析未配置。无新UI布局、SQL、schema、依赖或事务变更；Mapper仅注释修正。

```sh
mvn -B -pl recsys-offline -am test -Dtest=AdSignalsPostgresTest,ExperimentReportPostgresTest,JobArgumentsTest,JobRunnerErrorTest,DagJobRedisTest,JobStatusRecordTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console
npx --no-install vitest run src/api/__tests__/errors.test.ts src/pages/project/__tests__/OpsConsoleErrors.test.tsx
npx --no-install prettier --check src/api/generated/errorCodes.ts
npx --no-install eslint src/api/generated/errorCodes.ts
```

9源路径见/tmp/recsys-ec06a-source-paths.json；排序路径NUL内容NUL SHA256 `0ca9d10cde4d5d573c2138833b88f2f8e67c1a2ffc985d043c61c67f4d364f7d`，验证后未改源码。13文件显式交付清单/tmp/recsys-ec06a-delivery-paths.json。

- `/tmp/recsys-ec06a-test.log`: `26834ad33676fda78d28483dbe4ea037f0ef3d84e512bdb3079129bae67420bc`
- `/tmp/recsys-ec06a-compile.log`: `140cd98f8bc7b892c5cddc591627c5320760217e675804782cdc1cd93e31dda7`
- `/tmp/recsys-ec06a-frontend.log`: `a6dddf6464f32a8da0d1ade254c173dd9538c57e30d5d01234aec121ea6029ee`
- `/tmp/recsys-ec06a-hygiene.json`: `44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`

## 交付与后续

正常任务commit/ff/push main持续授权；保护8文件、暂停后缀与唯一原目录worktree，保留测试输出/数据/env/模型等，不清理。回退代码会恢复原假成功和无效曲线风险，不撤销已发布的有效曲线或文件。基线H已交付82a4a07a，CI38815509098最近安全/自然成功、其余运行；最终CI仍须EC08核验最终SHA五门。
