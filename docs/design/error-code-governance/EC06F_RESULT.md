# EC06f Bandit/FTRL 发布验证

DONE/PASS（本地），基线aaa3b01ac40394c31b887b7048908b58b320a1b1。两作业发布异常不再吞掉后宣称已写。Redis读取失败不能作为缺失/坏JSON从零；原缺失/坏JSON/不兼容模型从零策略保留，固定目录码可见。Bandit完整特征顺序、模型结构和有限数校验；FTRL完整解析坐标后loadState，训练产物发布前校验；公式/源/随机顺序/JSON/键保持。

FTRL仍先weights后state，第二键失败提示部分更新并保留真实效果，无重试或假回滚。100正样本阈值和坐标布局具名；严格数值参数，原epochs=0空模型发布保持，原incremental选项存在语义保持。Bandit空曝光/FTRL无物品或少样本明确失败。

目录新增8码共241，旧233/8族保持；7源文件，无SQL/schema/依赖/数学类修改。

## 验证

- 改前13真实PG/Redis通过；改后58专项/8类首次全过：Bandit9、Ftrl10、纯LinUcb9、FtrlProximal3、FtrlHashing4、Args4、Runner5、Status14。
- Bandit完整矩阵/样本奖励、FTRL完整golden/default/replica/incremental及改变seed/物品池顺序会改变模型的反例均保持。缺/坏JSON/错误顺序模型、损坏n/非数值坐标、非法参数、非有限输入/训练、样本计数溢出不误发布。
- 真实Redis ACL拒绝get/set，FTRL仅weights可写而state被拒绝：失败无完成日志/原始异常展示；第二键失败保留实际第一键新值与第二键旧值，恢复后golden通过。读取故障无从零覆盖。
- 完整offline及依赖497项/84类，0失败/错误，1个原CI-only PostgresIntegrationGateTest探针skip。真实PG/Redis/pgvector测试均运行。offline357/52类；其余common/ad-common/content/recall/rank/embedding共140项。耗时7分21秒，2324exit0。
- 前端目录57项、全前端182项/26文件（为下阶段EC07基线，未改其源码）、生成物Prettier/ESLint、Python7、目录/606Java90XML45SQL守卫、24模块compile/diff通过。94503专项、10464目录前端、11674全前端、83071编译/hygiene均exit0，无未决失败。
- Hygiene无blocking finding，唯一HF-001把已提取MIN_POSITIVE_SAMPLES=100常量识别为inline magic，判不适用；Javaformatter/独立静态分析未配置，COMPLETE_WITH_LIMITATIONS。

7源指纹`119733371340214816b71ad9a79f0398fd7fb1c7f2decfde32b4f9cf731a73ed`（排序路径NUL内容NUL）；/tmp/recsys-ec06f-{source,delivery}-paths.json，验证后无源码修改。12文件交付（7源+设计/本结果/EC06父结果/进度/总报告）。

- `/tmp/recsys-ec06f-baseline.log`：`040709961544b114feec1f06f7170516d5d4e40d5ac6faa74efb38ff752558a7`
- `/tmp/recsys-ec06f-test.log`：`a889c1d5a619ca4d091d47552f9e014150f46f253e41003df2f1b820f2eddd72`
- `/tmp/recsys-ec06f-offline.log`：`97fdaed2f94ed3a155ecda3455ce49f746a6c484dec4f1859344a23962145b55`
- `/tmp/recsys-ec06f-compile.log`：`5816e7baf0a51cfd3622755ef37c6d2cdd21682abc14cf5e4f1846fd5d46a9d6`
- `/tmp/recsys-ec06f-frontend.log`：`6ccfbdcd7bbfaafc90e2703dfa72418bcdc2bff1255c4cf3b2ce100f31dd3b0a`

正常Git授权，8保护文件/暂停后缀/唯一工作树保持；不清理数据或部署。EC07B_DESIGN为下一片未提交文档，不纳入本片交付。E CI38821288391最后运行，D/C2被取消，不能称最终全绿。父EC06本地完成，见EC06_RESULT；EC07/08及最终SHA五门CI继续。
