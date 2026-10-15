# MP04a：广告目录与三类数据源

本片DONE/PASS；源指纹 `8ff893d62be52fbd7c282a7ab0d3c9a3870c7c63b549f616303ea204da0da1b0`。验证后只同步文档。

ShardedAdCatalogReader和AdRepository的业务SQL全部进入Mapper/XML；sharded/adDb/derived三个包精确绑定原DataSource，各会话不新建连接池。分片目录的逐行ResultHandler保留原Map填充和异常降级；关键词保留原bid排序再将NULL回填0；ANN仍先取广告向量，再经目录校验状态/回填出价。HOT缓存和模型逻辑保持。

AdShardingConfig仅连接装配及会话参数SET，不含业务查询，作为基础设施例外保留。真实测试加载原sharding.yaml规则，两个独占PG容器验证JOIN、ANY数组、聚合及跨片LIMIT；另用隔离schema验证广告向量/用户向量不会串源。未改历史SQL及分片规则。

## 验证

- 旧实现6项：分片4、向量2全通过。`/tmp/recsys-mp04a-sharding-before.log`、`/tmp/recsys-mp04a-vector-before.log`。
- 迁移后8项真实PG全部通过，增加会话工厂DataSource身份和Mapper隔离断言。`/tmp/recsys-mp04a-after.log`。
- 最终宿主18项（上述8、推荐Boot3、广告点击/转化配置边界7）0失败/错误/跳过，BUILD SUCCESS。`/tmp/recsys-mp04a-hosts-final.log`。首次宿主运行与编译同时操作生成目录，发生proto编译竞争；改为串行重跑后通过，后续Maven必须串行。
- 全仓24 reactor编译成功，`/tmp/recsys-mp04a-compile.log`；Hygiene无阻塞，`/tmp/recsys-mp04a-hygiene.json`。原无统一formatter/独立Java静态分析的限制保持。
- 上批MP03d远程CI38716894284 SUCCESS：https://github.com/lirji/recsys/actions/runs/38716894284 。本片远程CI待推送后观察。

## 下一步

继续MP04b广告主写路径；必须在真实双分片验证Snowflake生成/回填与部分更新、删除、向量复制。之后MP04c及全部后续包，SQL最终验收后统一错误码。
