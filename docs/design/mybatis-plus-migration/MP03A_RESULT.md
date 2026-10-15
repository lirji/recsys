# MP03a：词形权重查询迁移

本片DONE/PASS；源指纹 `f3d66de996c43324fca1b047bceccc01cda06d976c8e242c34c2e0d0bcddc1ef`，验证后仅同步文档。

IdfWeighter 的批量词形SQL迁入LexemeMapper/XML。保持 PostgreSQL English 分词、单词形筛选、单次数组查询、中性权重、缓存与缺失数据库降级；类型明确的LexemeAlias独立为持久层record。Query配置复用主源工厂及原连接池，无业务契约改变。

## 验证

- 旧实现：common30项、原mock调用次数1项、新真实PG词形/缺依赖恢复2项，共33项通过。`/tmp/recsys-mp03a-before.log`。
- 迁移后同33项全部通过，0失败/错误/跳过。`/tmp/recsys-mp03a-after.log`。
- 推荐宿主及词形回归6项全部通过，验证多模块共享工厂实际扫描与装配。`/tmp/recsys-mp03a-hosts.log`。
- 全仓增量编译及Hygiene结果见 `/tmp/recsys-mp03a-compile.log`、`/tmp/recsys-mp03a-hygiene.json`；无统一formatter/独立静态分析的既有限制保持。
- 新依赖均使用已冻结父BOM版本，Testcontainers仅测试范围。历史SQL及原用户文件不变。

## Git及后续

MP02b已正常ff合并/push main：fe410e665d809802e92663f24b22bf8aa2148e9b。MP02a CI 38712301983全部SUCCESS；MP02b CI 38713385372运行中。继续MP03b召回输入读取，最后MP15才做全项目结论，之后继续统一错误码。
