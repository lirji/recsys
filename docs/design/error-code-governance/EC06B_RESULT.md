# EC06b 归因身份与质量发布验证

DONE/PASS（本地），基线c6921b8fc5e1a4521c831cca18e56cb743598f26，按EC06B_DESIGN实施。EC06仍有DataQuality、Ocpc、Multimodal、Bandit、FTRL五个作业及EC07/08待完成。

归因PathEvent保留long ID、Touch保留具名时间与类型，显式事件/模型枚举替代数值类型码和路径数组下标。2^53相邻广告不再合并；NULL映射0、原排序、三模型和归一公式保持。未知model仍回退position并给固定码，不回显参数。无转化/CSV失败不再完成。

质量严格参数校验、无曝光/无合格广告明确失败；CSV或Redis错误传播专用部分发布码，不回滚既有键/行。逐行全部值先校验再写当前键，非法数值或四位舍入饱和不伪零。显式修复零点击+零先验的0/0：原QUALITY_OPTIONS第三广告曾发布NaN的round结果0；现在在该广告发布前失败，前两广告与原CSV行保留。纯QualityScore/MultiTouchAttribution公式未改。

目录7个新码，总223，旧216及8族保持。8源、12交付文件；无SQL/schema/依赖/事务变化，Mapper仅说明精度注释更新。

## 验证

- 59后端/7类全部通过，0失败错误跳过：AdAnalysis14、MultiTouchAttribution7、QualityScore9、JobArguments4、JobRunnerError5、DagJobRedis6、JobStatusRecord14。真实独占PG/Redis与临时子JVM完成，连接释放通过。
- PG的9007199254740992/9007199254740993/Long.MAX_VALUE和NULL四身份分别保留；相邻大ID进入同一转化路径时，三模型均保留两触点，各0.5信用，总和1。原位置/线性/时间衰减三份完整CSV SHA保持；NULL时间/用户原排序和两源窗口测试不变。
- 默认质量完整SHA保持。合法零先验的前两行与旧QUALITY_OPTIONS一致；测试把原已知坏第三行拼回后核对旧完整SHA，明确不认可该旧行。遇到原0/0第三广告时专用失败码、前两行字节相同、第三旧键保持；正常重跑恢复。
- 真实Redis ACL拒绝第二广告写入：失败码、NOPERM根因、第一键/一行CSV保留、第二旧键保持。输出路径为文件时两个Job均失败，无完成日志；修复后正常SHA恢复。
- 无事件/无转化/空曝光/门槛全部过滤分别安全码；负值/裸值/NaN/Infinity/权重和溢出/半衰率不可表示等在副作用前拒绝。真实PG NaN、正负Infinity、1e20质量输入不发布，并可补正恢复。
- 前端57项、生成TS格式/ESLint、Python7/目录check、605Java90XML45历史SQL守卫、24模块compile、diff/hygiene通过。Hygiene无finding；Javaformatter/独立静态分析未配置，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。

```sh
mvn -B -pl recsys-offline -am test -Dtest=AdAnalysisPostgresTest,MultiTouchAttributionTest,QualityScoreTest,JobArgumentsTest,JobRunnerErrorTest,DagJobRedisTest,JobStatusRecordTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console
npx --no-install vitest run src/api/__tests__/errors.test.ts src/pages/project/__tests__/OpsConsoleErrors.test.tsx
npx --no-install prettier --check src/api/generated/errorCodes.ts
npx --no-install eslint src/api/generated/errorCodes.ts
```

源码排序路径NUL内容NUL SHA256 `3b3d31aaa722610b39323a906abd5924929501217b17cf10ed3a73e5ca54cf79`，8源清单/tmp/recsys-ec06b-source-paths.json，验证后未改源码；12交付路径/tmp/recsys-ec06b-delivery-paths.json。

- `/tmp/recsys-ec06b-test.log`：`73d5c05fcc117ab65e918173f66a5f8899b862087a62dee17156f0e3d73384bc`
- `/tmp/recsys-ec06b-compile.log`：`91b07a999a867b9e523562c7bc0f573afd80eac2c3ed6437b5c7c7386b3a890e`
- `/tmp/recsys-ec06b-frontend.log`：`8d7d293fe9b307a144868ce6f04eecad41afdbbbc3436cc248448445dc2bfec9`
- `/tmp/recsys-ec06b-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`

正常commit/ff/push授权；8保护文件与暂停后缀不混入、唯一原目录worktree、忽略数据/env/测试输出不清理。回退会恢复大ID失真与假成功，不撤销Redis/CSV已写效果。基线A c6921b8f CI38816279798最近安全/自然成功、其余运行，不称五门全绿；最终EC08核验最终SHA。
