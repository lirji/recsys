# MP02b：目录与全文检索迁移结果

本片 DONE/PASS；全项目迁移继续。验证源指纹 `f8298d6333dee3edcab2a01b95ae12ab2c4a10fd2f82a8776f1a94c301d1bc5e`，之后只更新文档。

## 实现及保真边界

9 个检索方法迁入 ItemCatalogMapper/XML，两个目录来源通过 CatalogTable 固定选择；服务层仅保留 IDF 增量权重筛选及委托。结果使用显式 record 构造映射，float8[]/text[] 使用类型处理器，查询 limit 参数绑定。

保留全文分词/停用词/权重/并列排序，窗口排名，行为过滤与评分阈值。SQL NULL 热度转零但排序使用原始列，避免 COALESCE 改变排名。空类别列表沿旧实现抛 DataAccessException，此片不改变错误契约。两来源测试使用互斥标识，避免误路由被相同数据掩盖。

## 验证

- 旧实现：新双来源10项+原FTS3项，13项真实PG通过，日志 `/tmp/recsys-mp02b-before.log`。
- 迁移后：上述13项+CRUD5项，18项通过，日志 `/tmp/recsys-mp02b-after.log`。
- 宿主回归：内容18+用户8+推荐3，共29项，0失败/错误/跳过，日志 `/tmp/recsys-mp02b-hosts.log`。
- 全仓24 reactor项目增量编译PASS，日志 `/tmp/recsys-mp02b-compile.log`。
- Hygiene 无阻断；无统一formatter及独立Java静态分析的既有限制保留。日志 `/tmp/recsys-mp02b-hygiene.json`。
- 原用户文件及历史SQL不变；无API/数据库结构变更。

## 交付与后续

MP02a已正常ff合并/push main：29eb343ca345d33530eaea607c1765795a9f3384。MP01 CI 38711518885被后续提交的并发策略取消，不能记为全绿；MP02a CI 38712301983尚在运行。继续MP03，最终MP15核验最新完整CI。
用户追加目标已写入PLAN第10节：SQL全部完成后统一错误码/提示展示与相关魔法值，顺序不变。
