# MP12a：混排候选可信频控

本片DONE/PASS。6源文件指纹 `0d7a12e5e5e4b2175ae069df1356f1c917cd214662cb1d26c82881de2fe27458`。

FeedCandidateRepository的摘要、索引探针及频控三条SQL进入addb FeedCandidateMapper/XML，配置和两个fixture构造入口同步使用实际SqlSessionTemplate。候选只转换为adId/advertiserId目标记录，VALUES通过foreach绑定；保留原位数上限、非法参数检查顺序、空候选不访问数据库和结果集合语义。

频控仍只计已接受CPM账本，用户/广告/广告主过滤、两级计数各自LIMIT阈值、statement_timestamp、上海自然日闭开边界保持；接受日与原预占预算日不混淆。所有语句5秒超时，索引名来自服务端固定清单，to_regclass的NULL仍按原错误拒绝启用。

## 验证

- 旧基线MP11c完整资金链378项，含混排11项；新增缺索引NULL及空候选不读账本保护后，旧路径15项（真实PG13+配置2）全部通过（`/tmp/recsys-mp12a-before.log`）。测试只在独占schema临时重命名并finally恢复，不改历史DDL。
- 迁移后18项全部通过，0失败/错误/跳过（PG13、配置2、默认关闭推荐宿主3，`/tmp/recsys-mp12a-after.log`）。真实验证to_regclass空值映射、跨广告/广告主/用户、未接受预占不计数和上海接受日边界。
- hygiene无阻塞（`/tmp/recsys-mp12a-hygiene.json`），统一formatter/独立Java分析工具的既有限制保持。混排真实浏览器5项通过，实际最终位置/预占/捕获/自然事实与Outbox一致（`/tmp/recsys-mp12a-feed-browser.log`）；全仓24 reactor编译通过（`/tmp/recsys-mp12a-compile.log`），45历史SQL/8保护文件与6源文件指纹不变。

## 后续

MP11c已交付b596eeb2，CI38737280136运行中；MP11b CI38736158900最终cancelled，取消前钱链/自然/浏览器/security已SUCCESS。完成本片后继续MP12b三模式交付、MP12c自然、MP12d混排持久化，之后全部离线/Flink和统一错误码目标。
