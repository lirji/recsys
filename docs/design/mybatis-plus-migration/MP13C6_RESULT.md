# MP13c6：R9三组向量版本发布

DONE/PASS。7源文件指纹`3ad536aa463234c17255560a6342baaad9081ce1a36de220e0ca90de0908e0de`，清单`/tmp/recsys-mp13c6-source-paths.json`。

ImportR9EmbeddingsJob的三表DELETE/INSERT进入R9EmbeddingMapper/XML，运行时DDL提取r9-embeddings.sql并补3表9列中文注释；历史18_advanced_recall.sql字节不变。输入预校验与64维/有限值/归一化/同版本规则保持。新R9EmbeddingStore私有MP工厂只加载manual XML，复用实际派生DataSource，ManagedTransactionFactory(closeConnection=false)将原给定连接留给原回调管理，不改变普通Spring工厂环境、不新增池或事务。

- 旧真实PG7项通过（before）：三组1001行跨500批次、引号中文版本与其他版本保留、可选缺组保留、输入非法/全空时不bootstrap、第三组第700行CHECK失败时全三组旧版本恢复。
- 旧实现实证：手动commit会提交外层先行写入，手动rollback会回滚外层先行写入。这是历史技术债，本次保持；不能声称外层rollback能撤销已发布版本。
- 新首次关联10项、最终R9 PG8+解析2+实际四源装配1共11项通过（after-probe/after）。三类向量完整内容、版本和范围保持；普通SIMPLE会话在手动发布前后可用，工厂环境身份不变；3表9列注释、仅两项原HNSW索引、真实客户端关闭与服务端退出通过。
- 6个业务语句均固定XML，版本/身份/PGvector参数绑定；delete立即flush，insert每500行flush。第三组晚失败仍为DataIntegrityViolationException，SQLSTATE驱动异常从MyBatis原因链还原并交回原JdbcTemplate分类。
- 四源装配加载真实R9Store但保持4个DataSource/4个SqlSessionTemplate，manual Mapper未进入任一普通工厂或Spring扫描。
- 完整离线153项/29类，0失败/错误/跳过（offline）；全仓24 reactor测试编译（compile）、hygiene通过。日志前缀`/tmp/recsys-mp13c6-`。
- Hygiene的HF-001为原事务catch(Exception)适配：必须统一回滚SQL及Mapper运行时失败，随后保留SQL原因或带cause包装并抛出，不吞异常/返回成功。Java formatter与独立静态分析未配置限制保持。
- 45历史SQL/8保护摘要不变，无新增依赖。未运行全仓clean verify或最终浏览器门禁，本片证据不替代MP15。

前一片d06e66c7的[CI38757206178](https://github.com/lirji/recsys/actions/runs/38757206178)四门禁SUCCESS，主build仍在执行；最近完整成功仍9d7897b5的CI38754987441。后续核验真实终态。

下一片LookalikeJob：派生库四查询迁移，先真实PG/Redis保护去重质心、种子并集、NULL、ANN上限和失败前旧人群包保留；本片已完成不代表MP13或整体目标完成。
