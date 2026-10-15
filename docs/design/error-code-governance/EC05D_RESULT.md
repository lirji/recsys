# EC05d 召回与样本生成结果

DONE/PASS（本地），基线cb701520cfdeb1d4d410c27754ffa4cfe1edc500，十二作业范围/行为变更见EC05D_DESIGN。父EC05剩余评估/报表/模拟作业、EC06～08继续。

缺输入而未重建的分支向DAG报告专用错误码；三种CF有输入但没有任何列表产出时也明确未发布。正常共现/稀疏用户过滤、IDF有语料但无合格词时的空词表发布、旧键保留策略均保持。样本文件的已知全负标签和CVR部分无广告物品关联维持有效输出并给目录警告；CVR全部过滤仅写表头则失败，不谎称未修改输出文件。

重复数字CLI解析移入JobArguments，finite小数及有副作用的非法范围在发布前拒绝；保留既有非正days/半衰期/窗口关闭语义，未新增武断上限。新增14码共189，原175/8码族保持。未改SQL/schema/算法/CSV协议/事务/Redis发布顺序或依赖。多键Redis写入仍不是原子事务。

## 验证

- 完整offline及依赖453项/83类，0失败/错误，1项CI专用Docker可用性探针按CI环境条件跳过；实际PG/Redis/pgvector集成全部运行。分类：common63/14类、content19/4、recall21/6、rank29/7、embedding8/1、offline313/51。
- 新真实PG/Redis召回测试4项，验证三算法正常候选/数值、合法无新候选用户、空/稀疏输入保留旧键、非法参数不清榜、输入修复后JobRunner重跑成功。原Hot/Cold/IDF/UserEmbedding真实查询/事务/晚失败断言保持。
- 三种样本的整文件SHA基线（默认、扩展特征、随机种子、严格时间、替代来源等）全部保持；新增全负标签文件成功且带码、非法参数保护旧文件。CVR真实分片/PG的整CSV、NULL/未来/删失/晚特征失败部分输出保持，新增全过滤失败和部分过滤警告。子JVM固定输出仅在临时目录生成；fixture显式打印受控码便于断言，生产出口继续由JobRunner负责。
- 首轮51项有9处旧断言要求无输入成功/旧异常类型，按已冻结契约更新为精确码，保留原存储和文件效果断言；最终完整回归通过。
- 前端错误/Ops57项、生成TS Prettier/ESLint，Python7、目录check、604Java/90XML/45历史SQL边界、全仓24模块compile与diff通过。Hygiene无finding，Javaformatter/独立静态分析未配置，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。

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

27源清单/tmp/recsys-ec05d-source-paths.json，排序路径NUL内容NUL SHA256：f42763bba82eebf4661c5333fa6fa026e8230db39532c3ae523e361b89a48dd5，验证后未改源码。

- `/tmp/recsys-ec05d-old-expectations.log`：`1963c1a50ced6f4481e03f7c0f2af4daff3b5be6094588906f80d7f02874ab3d`
- `/tmp/recsys-ec05d-offline.log`：`b640b33bb1ddb61b467fc8a9155949551d6d717cb5f1f8342f9a37cdfe1c1fa5`
- `/tmp/recsys-ec05d-compile.log`：`921e79c39a68fd9895260f8e6e9f7b4336b0c793bbd7ef7a9d370f1ee595983b`
- `/tmp/recsys-ec05d-frontend.log`：`50d8cd5de28051ad17c3b2519909f6ab26b26c8391cb66537286b162f07b2370`
- `/tmp/recsys-ec05d-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`

31文件正常任务commit/ff/push授权，保护8文件和暂停后缀。原目录唯一worktree，临时测试输出/忽略数据/模型/env/构建产物保留。回退恢复旧错误提示/假成功语义，不能撤销已生成结果。基线C CI38810074692四门SUCCESS、build仍运行；最终EC08必须最终SHA五门成功。
