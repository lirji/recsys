# MP13a2：六个作业的共用正反馈路径

本片DONE/PASS；MP13仍IN_PROGRESS。14源文件指纹`958df7bfff535d2dcae07f4b9710a78b725d740a1011979661c6342cc2a01087`，文件清单见本提交与`/tmp/recsys-mp13a2-source-paths.json`。

BehaviorQuery不再生成SQL，BehaviorSource只接受原user_behavior/behavior_log两个稳定code；固定表选择、正反馈过滤、可选maxTs和最大epoch聚合进入PositiveFeedbackMapper/XML。ItemCf/Swing/UserCf已无业务SQL；BanditStats/FtrlTrain/UserEmbedding仅共用部分迁移，其余SQL在后续子片核销。

ResultHandler在会话内逐行消费，原各算法List重复行、Set去重、超活跃截断保持；没有改成全量查询List，也未新增fetchSize/事务/查询超时。Weighted显式保留NULL，UserEmbedding延续原getDouble(NULL)=0及时间衰减，最大epoch空集仍NULL。XML无任意SQL入口或原始表名插值。

## 证据

- 旧实现新增真实PG三项保护全部通过，`/tmp/recsys-mp13a2-before.log`：重复行、评分阈值、微秒时间上界、两来源隔离、NULL与Long.MAX_VALUE、非法来源、回调失败关闭连接及后续读取。
- 新实现专项4项全部通过，`/tmp/recsys-mp13a2-after-final.log`，包括四源会话与从实际MapperScan注解读取配置的主源绑定检查。
- 首次after四项中一项预期异常类型失败：MyBatis包装回调异常。最终精确断言MyBatisSystemException及同一个原始IllegalStateException身份，保留pg_stat_activity零连接及后续读取断言；没有修改业务错误分类或吞异常。内部异常包装差异符合已批准架构的框架异常边界，错误展示统一仍在SQL阶段之后。
- 全部离线68项/15类通过，0失败/错误/跳过，`/tmp/recsys-mp13a2-offline.log`；全仓24 reactor测试编译通过，`/tmp/recsys-mp13a2-compile.log`。
- 45历史SQL及8保护文件摘要保持。未触碰共享数据库、Redis、模型或用户已有配置。

## 结构与后续

主源Mapper按persistence/mapper/primary扫描，XML位于mapper/offline/primary；数据源身份、缓存和四路隔离复用MP13a1基础。剩余离线导入/向量/特征/训练/报表继续按单作业或共用路径迁移；尚未验证的真实分片和Flink留后续。MP12d远程CI38741661134五门禁已全部SUCCESS，原生窗口夹具远程问题已关闭。

Hygiene无阻塞（/tmp/recsys-mp13a2-hygiene.json）；BehaviorSource的相似枚举提示命中忽略的Python venv中Torch SourceType，与Java行为读表无关，人工审查后无需复用或修改。统一Java formatter/独立分析工具未配置，限制保持。
