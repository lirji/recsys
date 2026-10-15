# FTRL完整模型对照的跨架构浮点修正

2026-10-16，DONE/PASS（本地）。仅测试与参考模型变更，生产算法、查询、排序、随机种子和序列化均不改。4源指纹`8f006eaa0454875a90344781d20d3bd3140e80bff0ebaebbf9caaa5a732ee5ab`，清单`/tmp/recsys-ftrl-tolerance-source-paths.json`。

## 失败与复现

6a05f2ec的CI38764359355 build在FTRL默认/增量两个精确JSON摘要断言失败。用同一FtrlProximal和固定原输入执行无数据库的纯计算探针，ARM返回旧JDBC摘要；独占amd64 Temurin21容器精确复现CI两个摘要，证明不是SQL输入变化。容器内仅把Math.exp替换为StrictMath.exp即恢复ARM旧摘要；生产代码没有做此替换。

| 输出 | ARM旧JDBC/探针摘要 | amd64 Math.exp摘要 | 最大数值差 |
|---|---|---|---|
| 默认 | cd2da7d0353ea49dfe292bbf2c5b605a93301cafd5e0e9b1f77f347368b13b12 | 3030a872ff8b037855cdee2c64086d287ad4e1850adcc23789853d2961e1e168 | 7.105427357601002e-15，最多18ULP |
| 增量 | e247772706416d5c0cc99d8df24cc2def1e71965e237edf59f14f9be6d93258e | fc4960000d4762132d1cd9471f85ac396c25fe368564840250aab6848569bdb4 | 2.842170943040401e-14，最多40ULP |

探针及结果`/tmp/recsys-ftrl-float-probe`；隔离镜像`eclipse-temurin@sha256:442a743d9272be15c9872915eab0f7a1b6bb45b7c16c613acf172e2d7483061d`（linux/amd64），运行时禁网络，临时只读输入、临时结果挂载，容器退出删除。

## 修正与证据

保存默认/增量/双读仓的旧完整权重和z/n状态JSON，值来自与迁移前摘要一致的模型；比较所有键、数组长度及每个数值，数值容差max(1e-14,128ULP)，近零使用绝对下限。所有amd64差值在容差内；不接受未知键、缺失坐标或非有限值。同一JVM重复运行仍保持精确摘要比较。

新增反例：改变seed、把负采样池改成按ID排序，两种情况完整模型对照均拒绝，验证没有弱化SQL顺序回归保护。

- 最终FTRL7项真实PG/Redis通过：`/tmp/recsys-ftrl-model-tolerance.log`。
- 完整离线205项/37类0失败/错误/跳过：`/tmp/recsys-ftrl-tolerance-offline.log`。
- 全仓24 reactor测试编译及hygiene通过：`/tmp/recsys-ci-fixtures-{compile.log,hygiene.json}`；Java formatter与独立静态分析未配置限制保持。
- 原CI attempt1真实失败保留；不将旧SHA的重跑或本地成功宣称为新提交远程全绿。CPA午夜夹具问题另见CONVERSION_CLOCK_RESULT；attempt2 money因Maven Central HTTP429未跑到原失败用例。

本修正与MP13e2可正常ff/push后核验新CI；继续MP13e3及原全部范围。
