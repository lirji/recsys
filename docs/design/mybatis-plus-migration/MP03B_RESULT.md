# MP03b：召回输入读取迁移

本片DONE/PASS；源指纹 `f3e0951daae670184e7029c0de16fd2e972ca28284a4244c60a394808fccec10`。验证后只同步文档。

BehaviorSequenceSource、RecentPositiveItemsSource、DbUserProfileReader 使用主源 RecallInputMapper/XML；I2I/Swing 的未预取回退复用原 RecentPositiveItemsSource，去除两份重复SQL。主源Mapper包与后续派生源包隔离，不改连接池和数据归属。

模型序列仍按每物品最后正反馈去重、按时间升序返回；旧种子仍包括所有评分、按item_id降序；空预取不访问DB。画像保留重复及NULL元素、非法JSON形状降级。负limit在两入口仍分别按原规则归一或抛异常。

## 验证

- 旧/新各39项全部通过：common30、输入5项真实PG、R9模型+PG2、原FTS召回单测2。日志 `/tmp/recsys-mp03b-before.log`、`/tmp/recsys-mp03b-after.log`。
- 宿主回归10项全部通过：输入5+R9 2+推荐3，无失败/错误/跳过。`/tmp/recsys-mp03b-hosts.log`。
- 全仓编译与Hygiene见 `/tmp/recsys-mp03b-compile.log`、`/tmp/recsys-mp03b-hygiene.json`。统一formatter及独立静态分析未配置的既有限制保留。
- 新依赖只复用已冻结版本；无DDL/历史脚本/外部契约变更。

## 交付与后续

MP03a已正常ff合并/push main：9c063f2c。继续MP03c，先普通向量/语义ANN，再版本化模型及语义ID；保持主源行为与派生向量的路由边界。全SQL完成后继续用户追加的统一错误码目标。
