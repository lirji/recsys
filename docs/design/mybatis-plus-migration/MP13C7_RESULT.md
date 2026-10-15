# MP13c7：Lookalike人群扩散查询

DONE/PASS。5源文件指纹`0a13c2eb9f87a11123643b5206e420aba2ce3dbefd72e1f4f8e54bf33a070843`，清单`/tmp/recsys-mp13c7-source-paths.json`。

LookalikeJob四条查询进入derived LookalikeMapper/XML：去重非空人群、种子用户、IN集合向量流式扫描和余弦近邻。质心计算、种子并集、参数默认值和Redis发布顺序保持。动态值全部绑定，PGvector显式ObjectTypeHandler保持setObject；不创建空透传Repository。

- 旧真实PG/Redis7项通过（before），新PG7+质心单元2+实际四源装配1共10项通过（after）。完整离线160项/30类0失败/错误/跳过（offline），全仓24 reactor测试编译（compile）及hygiene通过。日志前缀`/tmp/recsys-mp13c7-`。
- 重复种子只让其向量参与一次质心，种子中的缺失向量用户仍进入最终并集；1101候选默认精确取1000最近用户；top-n=0保留种子且仅处理指定人群。
- NULL种子用户仍变成Redis字符串null，ANN结果NULL身份保持旧getLong(NULL)=0；Long.MAX_VALUE不截断。NULL/缺失向量跳过，人群为空/无可用向量时旧包保留。
- 负limit与缺表在Redis删除前失败，旧包保留，下一次合法查询成功；真实连接关闭/服务端退出验证通过。
- 宿主真实MapperFactoryBean绑定派生SqlSessionTemplate；向量扫描继续ResultHandler在会话内消费，没有全量字符串中间列表。
- 纠正旧注释：缺user_embedding表实际上抛错；Redis DEL+SADD并不原子。仅保持当前行为，未新增Redis事务或故障补偿，不将SQL查询失败时旧包保留泛化为所有Redis失败都安全。
- 无新增依赖/DDL；45历史SQL和8保护摘要保持。Java formatter及独立静态分析未配置限制保持。

R9=df2ca1a2已交付，[CI38758681589](https://github.com/lirji/recsys/actions/runs/38758681589)在途；前序d06e66c7 CI38757206178被后续提交取消，不记成功。最近完整成功仍9d7897b5 CI38754987441。

下一片MP13d1 HotJob：先保护主源热度加权/排序/来源/时间/空榜与失败，然后迁移固定XML；其余MP13、MP14、MP15及错误码阶段仍未完成。
