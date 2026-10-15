# MP13d2：冷启动类目统计

DONE/PASS。5源文件指纹`99d5a1ec28c3f17807a75aad32e16e8681ffaf6d464952c04c5396fa61009f1a`，清单`/tmp/recsys-mp13d2-source-paths.json`。

ColdBanditStatsJob关联聚合进入主源ColdBanditMapper/XML。行为表和目录表分别使用已有BehaviorSource/CatalogTable固定分支，阈值绑定；ResultMap显式映射具名Stats(category,impressions,positives)，保持BIGINT计数，移除业务中的String[]下标和计数字符串往返解析。

- 旧真实PG/Redis5项通过（before），新类目5+真实四源装配1共6项通过（after）。完整离线172项/32类0失败/错误/跳过（offline），全仓24 reactor测试编译（compile）、hygiene通过；日志前缀`/tmp/recsys-mp13d2-`。
- 双来源独立选择及全部组合覆盖；重复行为计数、CLICK/LIKE/PLAY/RATING阈值、NULL评分/未知行为、缺失物品/NULL物品和NULL类目排除、空字符串类目保留与原一致。
- 空结果不覆盖总数，类别消失不删除原键；新总数只反映本次查询分组，仍沿用原缓存更新边界。非法来源与缺目录失败不写Redis，真实连接关闭验证通过。
- MapperFactoryBean实际绑定主源；无新Repository、依赖、DDL、缓存协议或事务。45历史SQL及8保护摘要保持。
- Java formatter/独立静态分析未配置限制保持。本片不替代MP15完整门禁。

[R9 df2ca1a2的CI38758681589](https://github.com/lirji/recsys/actions/runs/38758681589)五门禁全部SUCCESS。Lookalike=2075bcb2、Hot=06368027与本片三个独立逻辑提交随后正常ff/push main，新CI另行核验。

下一片BanditStatsJob剩余曝光查询：先固定真实曝光/正反馈输入与Redis模型矩阵、增量恢复、空输入保留，再迁移曝光Mapper/XML。后续MP13、MP14、MP15及统一错误码目标保持进行中。
