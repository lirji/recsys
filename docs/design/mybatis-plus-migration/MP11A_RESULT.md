# MP11a：三族可靠Outbox

本片DONE/PASS；远程门禁的原生页签问题已独立修复并通过本地三套14项浏览器验收，见NATIVE_TAB_CI_RESULT。14个源文件指纹 `9c9b21d4ceb18b35575f59f64afaea2c394082073d5d61ca9165157f65095726`。

BillingOutboxRepository的10条语句进入addb BillingOutboxMapper/XML，三族EventConfiguration和全部fixture/故障子类同步使用SqlSessionTemplate。原Java字符串替换删除，XML只通过封闭EventFamily选择固定Outbox/Inbox/汇总/重放审计表，没有外部SQL片段或标识符插值。

领取使用`UPDATE RETURNING`，声明affectData=true、flushCache=true、useCache=false；保留clock_timestamp、30秒租约、20次尝试、exhaust最多100条、稳定排序与SKIP LOCKED。published/failed仍要求当前fence和未到期租约；DEAD重开和审计仍在单条CTE内原子执行。10条语句都保留5秒超时，事件载荷及分区键没有改变，等待Kafka期间不持有领取锁。

## 验证

- 旧基线：MP10b完整资金链374项；另新增三项真实PG保护，旧BillingEventsPostgresTest共12项全部通过（`/tmp/recsys-mp11a-before.log`）。
- 新保护验证领取自动提交/显式事务回滚、跳过被锁的最早事件、重放审计键冲突回滚状态/次数/fence，不能用Mock替代数据库语义。
- 迁移后三族PG/Kafka及配置/worker专项60项/10类全部通过，0失败/错误/跳过（`/tmp/recsys-mp11a-after.log`）。
- hygiene无阻塞（`/tmp/recsys-mp11a-hygiene.json`），统一formatter/独立Java分析工具的既有限制保持；推荐宿主3项与全仓24 reactor编译通过（`/tmp/recsys-mp11a-host.log`、`/tmp/recsys-mp11a-compile.log`）。
- 显式sandbox浏览器4项通过，实际Outbox/Kafka投影与最终账务对账通过（`/tmp/recsys-mp11a-sandbox-browser.log`）。
- 45历史SQL及8个保护文件摘要不变，只使用独占测试容器；没有修改数据迁移或产品事件契约。

## 后续

MP10b/c已交付e0949431，CI38734120451混排原生页签hidden测试失败，日志及独立修复见NATIVE_TAB_CI_RESULT；MP10a CI38732423173五门禁成功。本片验证及Git交付后继续MP11b三族投影，再做MP11c只读对账。全SQL与统一错误码目标均仍未完成。

原生页签修复独立提交34a2a7a7；本片与该修复连续ff合并/push，最新远程HEAD门禁需继续核验，不能把旧失败运行改记成功。
