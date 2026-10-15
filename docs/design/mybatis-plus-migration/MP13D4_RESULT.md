# MP13d4：IDF语料统计与词形别名

DONE/PASS。6源文件指纹`66ef8a4b5b689517f00cf62b355c552934a52351722fab36c85e08087e9f7edd`，清单`/tmp/recsys-mp13d4-source-paths.json`。

IdfJob的计数、ts_stat文档频率、原文本扫描和raw token词干映射进入主源IdfCorpusMapper/XML。目录来源用CatalogTable固定分支，ts_stat内部查询也只有两个固定字面量；text[]复用TextListTypeHandler的createArrayOf/setArray，保留原2000词批次。各扫描继续ResultHandler，IDF公式/6位格式化、QueryTokens和Redis发布逻辑不变。原IdfJobPostgresTest同步Mapper构造并补独占表/4列注释，历史初始化SQL未动。

- 旧发布6+已有词干1共7真实PG/Redis测试通过（before），新关联8项含真实四源装配通过（after）。完整离线184项/34类0失败/错误/跳过（offline），全仓24 reactor测试编译（compile）、hygiene通过；日志前缀`/tmp/recsys-mp13d4-`。
- N=4中包含NULL文本空文档，movie/movies与studies/studying同词干df=3，IDF=1.223144；重复rare在一篇文档只计df=1，IDF=1.916291。min-df、停用词、双目录与全部别名Hash完整内容一致。
- 3001词跨原2000词数组批次，两Hash每词值均1.000000；空语料保留旧Hash/总数，有语料但无合格词时删除两Hash并更新总数。
- 非法来源/缺title_tsv在发布前失败，旧快照保留；注入第二alias Hash rename失败时，canonical已更新、alias与总数仍旧，临时键清理且原异常传播。实际PG/Redis参与，不宣称三键原子或网络故障下强一致。
- Mapper实际绑定主源；PG连接真实关闭、服务端退出通过。无新增依赖/事务/部署单元，45历史SQL与8保护摘要保持。
- Java formatter及独立静态分析未配置限制保持，本片不替代MP15完整验收。

[2d1712b5的CI38760632548](https://github.com/lirji/recsys/actions/runs/38760632548)五门禁全部SUCCESS，覆盖Lookalike/Hot/ColdBandit；上一R9 CI38758681589亦全成功。Bandit=895d7e23与本片分别提交后正常ff/push main，新CI另行核验。

下一片BuildFeaturesJob：原评分计数/NULL均值/总体标准差、四位归一化与交叉特征、双来源、Hash合并和分阶段失败边界先旧PG/Redis保护，再迁移五查询。整体SQL及后续统一错误码目标继续。
