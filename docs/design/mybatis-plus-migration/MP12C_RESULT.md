# MP12c：自然交付持久化

本片DONE/PASS。6源文件指纹 `ba1837cab3d2ae55770a2351f4a49d3788003844b2ce18a1f2fac95d3aea577a`。

自然交付的8条SQL集中到engine primary NaturalDeliveryMapper/XML；Repository保留请求/政策与冻结结果的JSON解释、授权及业务约束，配置显式使用sqlSessionTemplate。Mapper原始record避免在owner检查前反序列化快照；参数绑定、完整列映射、NULL结果、UTF-8 4MiB限制与原错误保持。

事务人工审查：Repository仍REQUIRES_NEW/READ_COMMITTED/5秒，从实际SqlSessionFactory获取同一个DataSource，既有事务管理器和会话共用Spring连接。每条SQL保留5秒超时；UPDATE RETURNING显式affectData/flushCache并禁缓存。数据库clock_timestamp与毫秒截断、30秒租约、subject/fence/有效租约条件、冻结影响行数保持；不跨owner持锁，不修改历史迁移。

## 验证

- 新增外层事务回滚保护后，旧实现12项（真实PG10+配置2）全部通过（`/tmp/recsys-mp12c-before.log`）：独立create/acquire/freeze结果在外层回滚后仍完整保留。
- 迁移后15项全部通过，0失败/错误/跳过（PG10+配置2+默认关闭推荐宿主3，`/tmp/recsys-mp12c-after.log`）。覆盖原资格/政策冻结、失去响应后的精确恢复、并发单领取、旧fence拒绝及真实空结果。
- hygiene无阻塞（`/tmp/recsys-mp12c-hygiene.json`），提示的事务人工审查已按上文源码和真实PG证据完成；统一formatter/独立Java分析工具缺失限制保持。

- 自然真实浏览器5项通过（`/tmp/recsys-mp12c-natural-browser.log`），唯一事实及原子Outbox核对一致，未执行Kafka投递。全仓24 reactor编译通过（`/tmp/recsys-mp12c-compile.log`）；45历史SQL、8保护文件和6源指纹不变。

## 后续

继续MP12d混排交付，随后全部离线/Flink/最终验收，再开展统一错误码与提示展示。
