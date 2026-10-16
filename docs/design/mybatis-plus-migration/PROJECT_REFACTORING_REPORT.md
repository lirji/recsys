# ProjectRefactoringReport：全项目 MyBatis-Plus 迁移

## projectSummary

MP00～MP15已实现、验证、正常Git交付且最终CI五门通过。业务SQL已统一为MyBatis-Plus管理的Mapper/XML，保持原业务模块、数据owner和运行边界。追加错误码EC01～EC08实现与最终本地验收完成，见[EC08_RESULT](../error-code-governance/EC08_RESULT.md)；整体最终Git/CI与本机Docker部署状态见根交接。

## baseline

基线`e7838776`，原22子模块；初始13模块/131候选Java、45独立SQL脚本，526是关键词命中数而非SQL条数。重构前全仓694项、0失败/错误、4既有按需跳过。八项用户/暂停文件保留；原企业可靠性S13B～S25继续暂停。

## problems / implementation

| 原问题/风险 | 处理结果 |
|---|---|
| Service/算法/Job直接SQL | 本模块具名Mapper/XML；业务计算、缓存、编排留在原调用层；清单全部核销。 |
| 多DataSource/默认扫描风险 | 四路工厂绑定原实际DataSource，精确XML/Mapper扫描；真实双分片/互斥schema/事务回滚保护；advertiser派生源非Bean，Boot主源继续创建。 |
| RETURNING/锁/时间/缓存语义 | 写返回行显式副作用标记；STATEMENT缓存、禁二级cache；保留锁序、影响行数、隔离和截止，见资金各片RESULT。 |
| 动态表名/SQL拼接 | CatalogTable/BehaviorSource/AdEventSource等固定枚举和XML分支；旧table字符串入口清除，无万能execute(sql) Mapper。 |
| 离线批次和Flink遗漏 | 离线逐作业真实PG/Redis验证输出及晚失败进度；Flink非Boot会话和最终shade独立JVM真实查询通过。 |
| 仅Mock不能证明SQL | 逐片先旧后新真实PG特征测试，独立JDBC核验仍保留；Java SQL回流/危险XML/历史脚本摘要新增CI守卫。 |

具体结构决定见[STRUCTURE_REVIEW](STRUCTURE_REVIEW.md)；开发规范见[持久层与Mapper规范](../../13-持久层与Mapper规范.md)。新增recsys-persistence仅复用类型映射与会话装配，不创建全局业务仓储或连接池。

## verification

生产代码基于本地`bc945903`，全仓24 reactor clean verify成功：1049项/202类，0失败/错误、4个按需跳过。3个浏览器宿主及CI=true Docker检查已显式通过；资金384/68类、自然75/13类、Chromium 4+5+5全部通过，见MP15_RESULT。前端98项/19类、类型检查和构建通过，ONNX自包含10模型与Python编码契约10用例通过。

MP14旧5/新6 PG+最终shade真实查询1通过；完整离线280/46类通过。静态边界扫描591生产Java/90 Mapper XML/45历史SQL通过，守卫6项测试通过。MP版本、许可证及已知漏洞核查见MP01_RESULT/TECH_SELECTION；security-scan是报告型门禁，成功不表示不存在漏洞。统一formatter和独立Java静态分析未配置，不声称静态分析全通过。

## behaviorCompatibility

HTTP/gRPC、数据库结构、MQ/Redis契约与业务算法保持，所有真实数据库测试仅使用独占测试组件。45历史SQL摘要不变；8个bootstrap资源承接既有运行时DDL。验证覆盖金额/资格/锁序/幂等、跨源路由、空值/时间精度、批次部分失败及资源释放；未测容量与性能，不承诺吞吐/延迟提升。

## rollbackPlan / nextMilestone

按PLAN第8节正常revert有界提交，禁止重写Git历史或操作共享数据；回退代码不能撤销已发生的业务写入。SQL阶段Git/CI已完成；错误码治理已完成实现与本地验收，后续按根交接完成最终交付及用户追加的本机Docker运行验证。计划整体已授权，不恢复原暂停企业可靠性任务。

## remainingRisks / technicalDebt

以下保留SQL迁移时发现的历史证据。Bandit/FTRL发布、AdCalibrate参数、Attribution大ID/部分失败、AdReport未知计数、oCPC故障分类和DataQuality假绿已由EC06对应片修复，见../error-code-governance/EC06_RESULT.md。R9手动事务传播、Lookalike/Hot缓存原子性仍为独立历史技术债；不能把SQL保护测试当作这些债务已解决。

新增已证实技术债：ImportR9EmbeddingsJob原手动事务会提交/回滚外层先行工作，迁移R9EmbeddingStore保持该边界并用真实PG保护；未来事务治理须独立决定传播策略。Lookalike/Hot沿用两条Redis命令删除后写入，窗口仍存在，不能宣称原子替换；查询失败发生在删除前时旧数据保留已实证，见MP13C7_RESULT/MP13D1_RESULT。

后续错误提示治理补充：Bandit增量成功日志lambda可与实际模型不一致，writeModel捕获写失败后仍可能记录已写；保持本次SQL兼容，见MP13D3_RESULT。

后续错误提示/参数治理补充：AdCalibrateJob的bins=0会先发布空曲线再越界报错，已用真实PG/Redis固定原行为，见MP13F1_RESULT；后续统一校验须在发布前拒绝。

后续类型与失败提示治理补充：Attribution原double[]路径会损失超过2^53的广告ID精度（9007199254740993→9007199254740992），本轮保留旧报表行为并用真实PG保护；应在明确输出变化的修复中改long具名路径。Attribution/Quality捕获CSV或部分Redis失败后仍记完成，后续统一提示需区分成功/部分失败。见MP13F3_RESULT。

AdReport原无效点击计数异常降级为0，不能区分“无无效点击”与“未知”。本次保留SQL兼容，后续错误提示治理应明确状态，见MP13F4_RESULT。

oCPC的days<=0实际仍排除epoch之前及NULL点击，旧说明“全部”不精确；无效lambda/旧k读取降级、Redis多广告主部分发布边界保持。后续提示治理应区分降级与正常输入，不宣称多键原子，见MP13F5_RESULT。

DataQuality检查全部因缺表跳过时仍记breaches=0和巡检通过，真实旧/新测试已固定该历史行为；后续统一提示需以已执行检查数和检查状态区分未检查与通过，见MP13F6_RESULT。

过渡装配清理已完成：原DataSource归属不变，advertiser派生工厂直接持有原非Bean无池源，保持Boot主源创建；三个表名字符串入口移除，固定XML来源枚举保留，见MP13F7_RESULT。
