# MP12b：三模式交付操作持久化

本片DONE/PASS。14源文件指纹 `dd9a818a8f68a834f644b1a52a87da3bd77c9aa1a6dd57f489e383f634fed08c`。

DeliveryOperationRepository的8条SQL进入engine primary DeliveryOperationMapper/XML。三模式通过XML固定表分支隔离，参数全部绑定；不再由Java replace拼SQL。三个Configuration显式绑定sqlSessionTemplate，在线fixture另建engineDs会话，避免误用已有adDb会话。

保留首次请求/bucket/reserve、owner先于JSON校验、可空plan及原序列化行为。create的INSERT与后续find仍独立提交；acquire使用affectData/flushCache且禁缓存，服从调用方Spring事务。所有语句保留5秒超时，数据库时钟/30秒租约、fence、旧plan比较、影响行数和首次冻结/增量结果不变。progress复用已有State枚举生成相同持久化值，未改变服务编排或历史迁移。

## 验证

- 旧实现新增真实事务回滚保护后，三模式PG24项通过（`/tmp/recsys-mp12b-before.log`）。回滚领取不消耗fence/租约，同事务重复领取也不会复用缓存结果。
- 迁移后45项/6类全部通过，0失败/错误/跳过（三模式PG24、CPC在线8、CPA在线10、推荐宿主3，`/tmp/recsys-mp12b-after-final.log`）。首次运行两处在线Spring测试遗漏会话bean；已补齐真实engine/adDb会话并保留多源装配检查，未放宽断言。
- sandbox真实浏览器4项全部通过，最终PG账本、Outbox/Kafka投影和资金对账通过（`/tmp/recsys-mp12b-sandbox-browser.log`）。
- hygiene无阻塞（`/tmp/recsys-mp12b-hygiene.json`）；统一formatter/独立Java静态分析未配置的既有限制保持。

全仓24 reactor编译通过（`/tmp/recsys-mp12b-compile.log`），45历史SQL、8保护文件与14源指纹不变。

## 后续

继续MP12c自然交付与MP12d混排交付，再完成离线/Flink及全量验收。SQL全部完成后才进入统一错误码与提示展示阶段，当前总目标仍IN_PROGRESS。
