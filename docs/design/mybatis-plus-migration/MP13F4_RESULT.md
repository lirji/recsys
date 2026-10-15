# MP13f4：广告与A/B报表查询

DONE/PASS。9源指纹`116b51573a09d0b50c6d7a027103b49acc22910df616fe01a27ae15154c95299`，清单`/tmp/recsys-mp13f4-source-paths.json`。

AdReportJob的位次/分桶/无效点击三查询与AbReportJob的原始桶统计/CUPED实验期/前置期三查询进入主源AdReportMapper/AbReportMapper及XML。值参数绑定，来源沿既有AdEventSource/BehaviorSource固定分支；无动态SQL字符串入口、可变来源字段、新事务/池/依赖或空透传Repository。算法、输出格式和查询调用时机保持。

- 旧10项真实PG专项通过；新10+四源装配1通过。子进程只写临时CSV，实际事件列TIMESTAMP，JVM Asia/Taipei明确验证Timestamp转换；结束前检查真实连接关闭及服务端退出。
- 广告曝光受since含下界过滤，关联点击/转化保留全量DISTINCT，重复事件不重复计费。NULL位次与真实0保持独立分组及原始NULLS LAST排序；收入/相关性NULL沿原JDBC读取为0。空输入不建报表，单桶不追加分桶段；晚bucket列缺失时保留已写位次/ALL行并抛错。无效点击统计异常原降级0继续保留，不能把未知统计解释为已证明0。
- AB原始统计排除NULL桶，LIKE等非CTR行为仍参与users；since包含、until排除，scene参数不能注入。原12列、显式基线/未知回退、阈值过滤、alpha/power、自定义来源及全量路径保持。
- CUPED pre严格[since-N,since)，允许NULL桶；post严格[since,until)，只保留有曝光的用户桶；跨桶按首曝光ITT且保留全部post结果，报告污染数。原getTimestamp().getTime的时区和毫秒精度保留，不替换成SQL epoch。NULL与真实0先分别分组、Java再合并；同毫秒按桶名裁决。覆盖率按曝光量计算，非用户比例；缺前置协变量、默认样本量不足回退、scene/微秒边界均验证。
- 六份完整旧CSV SHA256：AD_ALL `d800d7dd5516e9521ba80ef3811ad2da381cd70806d33b8c17b27f9aab6fb325`；AD_SINCE `e9004f4f773800b5ea6036a87fb204f1380e20565e0de5c88b322e400f1737d0`；AB_RAW `d5bd68634764beba785c222132f1f0a80b4e2283b08683c05bf983bb7f789052`；AB_OPTIONS `6ebbba2b3b112dd4c50d680dbb59ac651611846caee5e17492f976ba264ce142`；AB_ALL `d636974845be506203474a67b98c558d74d36d1fa6e261d9cb4224ac7d9312ec`；AB_CUPED `6a8f4ac25f2e0c7890ed9536160b9893ce94364b254fb374f3c83f0740d1f76c`。新路径全部逐字节一致。
- 首轮旧测试覆盖率预期误按用户比例0.8，原算法按曝光比例28/38=0.736842；修正测试预期并按种子数据校正B桶点击9，复跑旧10项全通过，未改变产品算法或放宽新旧对照。
- 完整离线262项/44类0失败/错误/跳过，全仓24 reactor测试编译通过，验证后未改源码。
- Hygiene IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻塞；Java formatter及独立静态分析未配置。45历史SQL和8用户保护文件摘要不变。

日志`/tmp/recsys-mp13f4-{before,before-final,after,offline,compile}.log`及`/tmp/recsys-mp13f4-hygiene.json`。本片不替代MP15全仓最终验收；后续仍有oCPC、数据质量、旧JdbcTemplate装配清理、Flink和统一错误码/魔法值治理。

0a4481f9的CI38776191398四专项SUCCESS、build仍在途。本片先本地提交，随后与oCPC相邻正常ff/push；不得将旧CI成功冒充本片远程结果。
