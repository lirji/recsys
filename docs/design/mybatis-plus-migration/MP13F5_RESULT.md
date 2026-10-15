# MP13f5：oCPC反馈控制输入

DONE/PASS。7源指纹`890bf1921467a5088b3ce124482acedab3eba295f10de59fab3b3dad1d1cc344`，清单`/tmp/recsys-mp13f5-source-paths.json`。

OcpcCalibrateJob四SQL进入分片OcpcTargetMapper与主源OcpcFeedbackMapper/XML。前者负责正目标OCPC广告的广告主AVG和广告归属；后者负责原点击窗口的花费与已到达转化。来源采用局部AdEventSource，days值绑定，Long[]继续通过已有LongArrayTypeHandler/PG bigint[]绑定。无跨库JOIN、新事务、连接池、基础设施或任意SQL接口，控制/阻尼/上下限/舍入/Redis读写规则不改。

- 旧9项真实双PG分片+主源schema+Redis专项、新9+四源装配1通过。逻辑分片加载宿主真实sharding.yaml规则，两片均实际查询；主源时间在测试外层事务冻结，精确验证端点。每例完成前检查连接closed、主源服务端会话退出、两物理池active=0，不靠关闭池掩盖泄漏。
- 目标按原广告主均值聚合、非OCPC/非正目标排除；64位广告/广告主及Long.MAX_VALUE数组成员保持精度。真实两分片分别产生结果，缺转化/缺花费保持旧k。
- 花费按点击窗口，曝光不受窗口限制；普通JOIN保留重复曝光×点击与点击×转化的乘积，不改成报表使用的DISTINCT。七天含边界、微秒外排除；转化now包含、未来转化排除，未来点击仍可关联已到达转化。
- days<=0沿原SQL仍以epoch为下界，NULL/1970前点击被排除；旧CLI说明“全部”不精确，记录后续提示治理，不借迁移扩展数据集合。转化权重沿点击elapsed，lambda=ln2的一天权重2，未来点击按完成度下限封顶，delay-correct=false和lambda缺失/非法保持原始计数。
- 非法/空/NaN/Infinity/非正旧k、lambda继续沿原回退；默认与自定义上下限、alpha=0、来源切换及无数据保持原结果。SQL/非法来源在Redis发布前失败；真实ACL仅允许首广告主键，第二写NOPERM抛DataAccessException而第一键已更新、第二旧值保留。
- 首轮ACL夹具仅开放GET/SET，未达到“第一写已成功”的预期故障点；调整为沿已有专项开放命令但限制目标key，最终明确断言根因NOPERM以及首成功/次保留，通过旧新对照，不放宽部分发布断言。
- 完整离线271项/45类0失败/错误/跳过，全仓24 reactor测试编译通过；验证后未改源码。45历史SQL与8用户保护摘要不变。
- Hygiene IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻塞；Java formatter/独立静态分析未配置限制保留。

日志`/tmp/recsys-mp13f5-{before,before-final,after,offline,compile}.log`及`/tmp/recsys-mp13f5-hygiene.json`。0a4481f9的CI38776191398五门禁全部SUCCESS。本片完成后与f4=a871eb76相邻正常ff/push，不替代MP15最终验收；下一片DataQuality最后七查询及旧JdbcTemplate装配清理，随后Flink、全仓门禁和统一错误码/魔法值。
