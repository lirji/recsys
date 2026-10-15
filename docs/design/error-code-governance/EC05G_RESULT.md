# EC05g 报表、延迟模型和人群投影验证

DONE/PASS（本地），基线56465b388ef4a79ceeffd37224b28ff879e7569f，范围按EC05G_DESIGN。EC05共享CLI与剩余作业审计、EC06～08继续。

AbReport无满足过滤条件曝光改为受控失败；CUPED缺开始时间专用码，统计参数严格校验。日期格式仍由PG原绑定参数解释，未新增格式收缩。正常raw/CUPED四完整SHA、广告旁路两SHA、baseline缺失回退/分组/首曝光ITT保持。极小alpha舍入到1的分位点会导致区间溢出，现于查询/输出前拒绝。

Delay样本不足或无效均值/lambda明确失败并保留旧模型，不再误称下游必不纠偏；min-samples=0前置拒绝，发布精度下lambda为0也拒绝。Lookalike缺向量人群继续保留旧包，成功人群沿原规则发布，整批最终以受控码失败；补向量后可重跑。top-n=0、完整long身份、种子并集、NULL/重复及默认1000扩散保持。AdExplore真实空事件成功发布total=0，warning准确说明未出现广告/创意旧键保留。

新增7码，共209，旧202项/8族保持。JobArguments增加实际使用的long解析；SQL/schema/依赖/数学公式/发布顺序不变。Redis多键/多群操作不具全局原子性，未扩大本次重构。

## 验证

- 最终覆盖84个不同后端测试/10类，均通过，无跳过：83项初轮全过；新增发布精度边界后AdSignals 13项全过，替代初轮同类12项。包含JobStatus14、JobArguments4、ExperimentReport11、Lookalike8、JobRunner5、AbStats13、DAG6、AdSignals13、AbReport1、DelayModel9。
- 真实PG/pgvector/Redis、临时子JVM：无报表/非法参数无文件，完整报表SHA；坏延迟参数与空/零样本不覆盖双键；缺人群向量保留旧包并失败、补齐重跑、Long.MAX_VALUE人群不丢精度、仅种子包和正常扩散集合均过。
- 原真实Redis ACL拒绝第二个延迟键时，第一个lambda已更新但mean仍旧值；探索后续SQL失败保留先前统计发布。旧连接释放、源副本和查询参数绑定检查保留。
- 新增极端历史延迟精度测试首轮失败：PG实际间隔为2100000.004166667天，原测试误把calendar interval的2100000天等同绝对elapsed上界，样本被过滤，得到SAMPLES_INSUFFICIENT。增加真实SQL数值范围断言并将测试输入上界设为2200000天后，精确断言MODEL_INVALID通过，原双键保持；产品代码与期望错误码未放宽。最终13项重跑通过。
- 前端错误/Ops57项、TS Prettier/ESLint、Python7/目录check、605Java90XML45历史SQL守卫、24compile、diff/hygiene通过。Hygiene无finding；Javaformatter和独立静态分析未配置，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。本片无页面布局变化。

## 命令与证据

```sh
mvn -B -pl recsys-offline -am test -Dtest=ExperimentReportPostgresTest,AbReportJobTest,AbStatsTest,AdSignalsPostgresTest,DelayModelTest,LookalikePostgresTest,JobArgumentsTest,JobRunnerErrorTest,DagJobRedisTest,JobStatusRecordTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-offline -am test -Dtest=AdSignalsPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console
npx --no-install vitest run src/api/__tests__/errors.test.ts src/pages/project/__tests__/OpsConsoleErrors.test.tsx
npx --no-install prettier --check src/api/generated/errorCodes.ts
npx --no-install eslint src/api/generated/errorCodes.ts
```

13源/tmp/recsys-ec05g-source-paths.json，排序路径NUL内容NUL SHA256：00a8b88d731e72ba6405910ada5a93b9b69df922ac5eacc69720762c63730cab。产品源码自83项验证后未变，后续只增加/修正AdSignals测试，其最终13项已重跑。

- `/tmp/recsys-ec05g-test.log`：`cc6ff4b4467d26f90743309767d26f4dd312be00a6c3bf75c8afba6ff6386938`
- `/tmp/recsys-ec05g-delay-boundary.log`：`f29d49e993498949997bd27262af6d68ca8fcb0c005bf6ed01b71f7324e92cf5`
- `/tmp/recsys-ec05g-delay-boundary-final.log`：`7c0f2b4e0ebd2c23404594929cb67663846613c5754f576da2682857b4c198fe`
- `/tmp/recsys-ec05g-compile.log`：`5035a60229d2674b3f1096c639fa2c4245396fa6f1c78357ba100500b7613d5d`
- `/tmp/recsys-ec05g-frontend.log`：`fcdd370b41f51609dc20d70ae168da83ecfa67509087ea45dbbeb68d9433995a`
- `/tmp/recsys-ec05g-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`

17交付文件按显式清单正常commit/ff/push，保护8文件与暂停后缀，保留数据/env/模型/测试输出，无清理或生产部署。回退恢复原假成功且不能撤销已发布结果。F已交付56465b38，CI38813384975运行中（安全/自然SUCCESS）；E38812115409最终取消，不冒称最终全绿。
