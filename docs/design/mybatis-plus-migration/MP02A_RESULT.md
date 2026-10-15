# MP02a：物品 CRUD 迁移结果

状态：本片 DONE/PASS；全项目任务继续。

验证后现存源文件指纹：`8212552fcba4f688baae714feb4e324314a7b97b10f45b16f8a40e95330355cf`；改名旧路径已退出，之后仅同步文档。

## 变更与兼容

- `JdbcContentService`/`ReplicaJdbcContentService` 改名为 `MybatisContentService`/`ReplicaMybatisContentService`，原缓存逻辑和开关保留。
- 主表按ID查询/计数实际使用 `BaseMapper<ItemRow>`；批量、原子upsert、排序与副本读取使用 `ItemMapper.xml`。
- `CatalogTable` 封闭枚举选择固定XML表名，避免拼接外部标识符。单表主键继续应用输入，不生成新ID。
- `ItemRow` 将持久化注解隔离于领域Item；text[]处理器保留顺序、重复和NULL元素；SQL NULL标签/热度仍按原逻辑转为空列表/零。
- 共享内容数据源工厂与用户模块共同装配，调用方改名注释同步；无数据库或API变更。

## 验证

| 检查 | 实际结果 | 日志 |
|---|---|---|
| 旧实现CRUD特征测试 | 5项真实PG全通过 | `/tmp/recsys-mp02a-before.log` |
| 新实现内容模块回归 | CRUD5项、原FTS3项通过；CI环境专用检查1项既有skip | `/tmp/recsys-mp02a-after.log` |
| clean后宿主/共享源回归 | CRUD5 + FTS3 + 用户8 + 推荐3，共19项，0失败/错误/跳过 | `/tmp/recsys-mp02a-hosts.log` |
| 全仓增量编译 | PASS | `/tmp/recsys-mp02a-compile.log` |
| Hygiene | 无阻断；原300秒TTL/50000缓存上限被改名扫描提示为advisory，实际仍受原@Value配置管理，未改其行为 | `/tmp/recsys-mp02a-hygiene.json` |

无formatter/独立Java静态分析的既有限制保持。不把旧FTS测试通过等同于FTS已迁移；该实现仍是JDBC，下一片MP02b处理。

测试覆盖数组特殊字符/NULL元素、数据库NULL、upsert与缓存失效、空/重复/不存在ID、权威与副本隔离、排序/计数、事务回滚。原SQL资源及用户文件未修改。

## Git与下一步

MP01已正常合并/push main：`e8462f5dbdf7c9d10b95be1ba765f04c39d54473`；CI `38711518885` 在本片验证期间运行，最终结果须查询，不提前记全绿。
本片交付SHA在PROGRESS_STATE记录；下一片MP02b先补目录查询的双来源和空值等保护测试，再迁移Mapper/XML。
