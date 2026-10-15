# MP12d：混排交付持久化

本片本地DONE/PASS。6源文件指纹 `ac46ec24001ca0bfcdba2cf6bd664e7fae5b1edcb8531c218d83e0ab10622af7`。

FeedDeliveryRepository的9条SQL进入engine primary FeedDeliveryMapper/XML。原始行按明确列映射，subject检查仍先于JSON解码；请求、混排设置、最终位置及广告准备结果仍由原领域约束校验。保留原JSON时间格式、数据库毫秒时钟、MAX_SNAPSHOT_BYTES与状态持久化值。

事务人工审查：从实际SqlSessionFactory取DataSource，REQUIRES_NEW/READ_COMMITTED/5秒不变；每条SQL5秒。acquire带affectData/flushCache并禁缓存，领取提交后才允许远程操作。freeze/progress保持subject、有效租约、fence、plan初始为空/旧JSON等条件和影响行数；已确认条目不能替换，完成后清租约，旧执行器不能释放后继租约。配置及两个fixture分别绑定engine主源会话，历史DDL未改。

## 验证

- 新增外层回滚不能撤销独立领取与冻结计划保护，旧16项（真实PG14+配置2）通过（`/tmp/recsys-mp12d-before.log`）。
- 迁移后19项全部通过，0失败/错误/跳过（PG14+配置2+默认关闭推荐宿主3，`/tmp/recsys-mp12d-after.log`）。包括原最终位置/资格恢复、预占与可信捕获、频控、并发单领取、旧fence与快照覆盖拒绝。
- hygiene无阻塞（`/tmp/recsys-mp12d-hygiene.json`），事务人工审查已按上文源码及真实PG证据完成；统一formatter/独立Java分析工具未配置的既有限制保持。

- 完整资金链382项/68类全部通过，0失败/错误/跳过；全仓24 reactor测试编译通过（`/tmp/recsys-mp12d-money.log`）。

- 完整自然门禁75项/13类通过（`/tmp/recsys-mp12d-natural.log`）；混排真实浏览器5项通过（`/tmp/recsys-mp12d-feed-browser.log`）。后续夹具窗口修复后的混排5项也通过（`/tmp/recsys-native-window-feed.log`），严格hidden/visible与资金/自然事实断言保留。
- 45历史SQL、8保护文件和6源指纹不变。远程MP12c CI混排hidden失败已发现，独立夹具整改与证据见NATIVE_WINDOW_CI_RESULT；本片需新CI实际确认，不能以本地PASS代替远程结论。

## 后续

本地门禁已完成，MP12闭环；随后MP13离线全部SQL、MP14 Flink、MP15最终验收与结构终审，再完成统一错误码/提示展示。
