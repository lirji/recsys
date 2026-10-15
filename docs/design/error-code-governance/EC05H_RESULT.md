# EC05h 共享来源与剩余CLI验证

DONE/PASS（本地），基线2717c95687e5629901de620d2702b69ec6d9dd49，范围按EC05H_DESIGN。EC05负责的35个OfflineJob、Runner和Flink边界本地验收完成；原计划明确归EC06的9种广告/模型/巡检作业仍待修复，EC07/08继续。整个统一错误目标尚未完成。

行为/物品/广告事件来源仍是原两值封闭集合与固定XML分支，新增安全码，默认值和合法来源保持；未知输入不会进入公开提示。裸/空白/多值使用JOB_INVALID_ARGUMENT；max-ts使用完整long，缺省null、0/负值原截止含义保持。内部枚举校验异常保持，CLI转换为公共异常，未扩展动态表名。

三Sync在任何ensureTable/full清除之前校验source-db，修正先清除再发现缺值的风险；合法源路径、1000行批次、水位和清理边界保持。SimAdUpliftData的users/seed/run-id严格解析，负users失败，既有users=0无副作用no-op保持，随机序列和双批提交不变。BuildFeatures通过共享入口治理来源，真实空输入的零写入与旧特征保留契约不机械改失败，原依赖错误仍传播。

目录新增3码，共212，原209/8族保持。28源、32交付文件，无SQL/schema/依赖/事务/算法变化。

## 验证

- 完整offline及依赖477项/84类，0失败、0错误；1项CI环境Docker探针SKIP，所有实际PG/Redis/pgvector集成已运行。模块：common47/8类，ad-common16/6，content19/4（含skip），recall21/6，rank29/7，embedding8/1，offline337/52。
- 新SourceArgumentsTest4项覆盖确切码、原默认/replica、未知来源不回显、裸/空白/多值和完整Long.MAX_VALUE；14个既有测试文件仅更新相应异常类型/展示码，保留原数据/CSV/部分发布/连接断言，未修改内部数学异常契约。
- 三Sync真实PG新增：full+裸/空白/多值source-db不得创建读模型；已有旧模型逐行保持；修正后full合法重跑补齐。原同库/跨库全列与数组/NULL、1000+2批次失败和外层回滚仍过。
- Uplift默认5000生成、分配批成功后事实失败的部分提交、外层回滚/提交、0无写入、坏参数无写入均过；所有既有评估/导入/召回/报表和相邻EC06旧基线同时回归。EC06旧假成功基线通过仅证明未提前改变其行为，不能宣称该债务已修复。
- 前端错误/Ops57项、TS格式/ESLint、Python7/生成check、605Java90XML45历史SQL守卫、24compile、diff和hygiene通过。Hygiene无finding；Javaformatter/独立静态分析未配置，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。无新增页面布局。

## 44个OfflineJob范围审计

- EC05a：DagJob
- EC05b：BackfillEmbeddingJob, PublishReportsJob
- EC05c：ImportBehaviorJob, ImportItemsJob, ImportR9EmbeddingsJob, ImportSemanticIdJob, ImportTowerJob
- EC05d：HotJob, IdfJob, ItemCfJob, UserCfJob, SwingJob, ColdBanditStatsJob, UserEmbeddingJob, GenSamplesJob, GenSamplesMtJob, GenSamplesImprJob, GenAdCvrSamplesJob, GenAdUpliftSamplesJob
- EC05e：SeedAdsJob, SimRecEventsJob, SimAdEventsJob
- EC05f：EvalJob, RecCalibrateJob, TuneFusionJob
- EC05g：AbReportJob, DelayModelJob, LookalikeJob, AdExploreStatsJob
- EC05h：SyncBehaviorLogJob, SyncItemCatalogJob, SyncAdEventLogJob, SimAdUpliftDataJob, BuildFeaturesJob
- EC06待完成：AdCalibrateJob, AdReportJob, AttributionJob, QualityScoreJob, DataQualityJob, BanditStatsJob, FtrlTrainJob, MultimodalBackfillJob, OcpcCalibrateJob

35个EC05作业均有对应片/实际现状说明；另外9个在原EC06中，不遗漏也不提前宣称完成。JobRunner/SystemOps/Flink证据分别见EC05A/B_RESULT。

## 命令与证据

```sh
mvn -B -pl recsys-offline -am test
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console
npx --no-install vitest run src/api/__tests__/errors.test.ts src/pages/project/__tests__/OpsConsoleErrors.test.tsx
npx --no-install prettier --check src/api/generated/errorCodes.ts
npx --no-install eslint src/api/generated/errorCodes.ts
```

28源/tmp/recsys-ec05h-source-paths.json，排序路径NUL内容NUL SHA256：92adef8e27eb976120bcca341ff045538d151c1dc6c73c173fb3f802635e4d0d；验证后源码未变。

- `/tmp/recsys-ec05h-offline.log`：`ff1230ac3da042ee6f5915d9387c0d9973626de845867e071efb0848f3b04279`
- `/tmp/recsys-ec05h-compile.log`：`682086b926fb740cb370a3c03ad413538dfd12411c19d741486276fe09263c3b`
- `/tmp/recsys-ec05h-frontend.log`：`ca55e4bbc672f4304252b92829f5c2c0044edf436f8252359b7c1eec7843f8f7`
- `/tmp/recsys-ec05h-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`
- `/tmp/recsys-ec05h-counts.json`：`c66932bb7c115fbecd9512974cb205c51135498d08831c29c00b9cfdd8dcd957`

正常commit/ff/push授权，32文件显式清单；8保护文件、暂停后缀和唯一原worktree保持，数据/env/模型/测试输出无清理授权。回退会恢复不安全参数时机；无法撤销已执行的合法清除/批次效果。基线G已ff/push2717c956，CI38814199492运行；F38813384975和E38812115409已取消。最终必须EC08最终SHA五门通过。
