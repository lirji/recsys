# MP13a1：离线四源会话装配

本片本地DONE/PASS；MP13a及MP13整体仍IN_PROGRESS。7源文件指纹`8353c708fe8ed5b8cf3b671fb62afdf1b59aca343e7f355f1638ddb91d74da11`。

OfflineAdShardingConfig显式导入primary/addb/derived/sharded四个共享会话工厂，复用原DataSource对象、连接池与环境配置，不新增连接池或路由框架。尚未迁移的作业继续使用原JdbcTemplate，单个业务入口不会双写；本片没有把任何离线业务SQL标记迁移完成。

offline POM明确声明recsys-persistence和MP boot3 starter。依赖树验证仍为MP3.5.17、MyBatis3.5.19、mybatis-spring3.0.5，与原冻结版本相同（`/tmp/recsys-mp13a1-dependencies.log`）。未新增选型或升级依赖，许可证/漏洞审查沿用既有版本证据。

## 验证

- 旧离线测试64项/13类全部通过（`/tmp/recsys-mp13a1-before.log`），含真实PG English stemming/df契约。
- 新增OfflineSessionBindingsPostgresTest，在独占PG四schema中验证会话绑定原DataSource对象、各XML目录隔离、STATEMENT本地缓存与禁二级缓存；每个会话实际查询所属schema。测试读取宿主实际Import清单，用独占数据源隔离环境配置，不连接本机或共享库（`/tmp/recsys-mp13a1-bindings.log`）。这不代表已验证真实ShardingSphere路由，路由仍在相应作业批中实测。
- 迁移后65项/14类全部通过，0失败/错误/跳过（`/tmp/recsys-mp13a1-after.log`）；全仓24 reactor编译通过（`/tmp/recsys-mp13a1-compile.log`）。
- hygiene无阻塞（`/tmp/recsys-mp13a1-hygiene-final.json`）；首次参数使用group:artifact，工具实际只接受artifact匹配，已用计划批准的两个artifact重新核验。统一Java formatter/独立静态分析未配置的既有限制保持。

## 后续

保留全部原业务算法与连接配置；继续MP13a共用正反馈查询/可信表来源参数，再按计划迁移各作业。JdbcTemplate过渡bean在其实际消费者退出后核销，不能现在删除。最终完整生命周期与实际分片/游标/批次/回滚门禁仍待后续批次。
