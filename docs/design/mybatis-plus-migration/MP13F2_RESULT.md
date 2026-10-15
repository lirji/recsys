# MP13f2：报表结构自举与JSONB发布

DONE/PASS。5源文件指纹`92b102c2275d5b9715f60a7692129fee4f81b08d092badf0d94d6d7b4e9407c6`，清单`/tmp/recsys-mp13f2-source-paths.json`。

PublishReportsJob的建表、表注释、索引及INSERT集中至主源ReportPublicationMapper/XML，补齐eval_report的8个字段注释。使用具名Report参数绑定CSV解析后的JSON文本，保留PG ::jsonb及ON CONFLICT(name) DO NOTHING。目录检查前仍先自举，索引/类型/唯一约束不改，不新增Repository、事务、池或依赖，历史06_eval_report.sql不改。

- 旧6真实PG专项、新6+实际四源装配1通过；完整离线241项/42类0失败/错误/跳过、全仓24 reactor测试编译通过。日志`/tmp/recsys-mp13f2-{before,after,offline,compile}.log`。
- 验证缺目录也自举、重复运行、表/8字段注释与分类时间索引；CSV中文、逗号/跨行引号、短行补空及超长行截断；完整列/行JSONB、源字节数和数据库创建时间保持。五种分类及未知名称、带SQL文本文件名均作为绑定值，大小写CSV筛选不变。
- 同名不同内容保持原JSON/身份/创建时间。畸形CSV、名为.csv的目录、真实CHECK约束拒绝的中间文件均不阻断后续正常文件，发布顺序仍按文件名排序。原主源外层事务回滚全部本次写入；结构损坏的bootstrap失败仍在发布前抛错。
- 所有文件只在测试临时目录，真实连接检查关闭及PG服务端退出。45历史SQL/8用户保护摘要不变；Java formatter及独立静态分析未配置限制保持，不替代MP15。

前批e5=f7f63963/f1=47ae6e02已正常ff/push；新CI38772834762尚未核验终态。最近完整成功CI38770885163对应da335f70。本片先本地提交，继续MP13f3归因与质量分报表的四条查询；注意原NULL排序、不同去重口径及部分发布语义。
