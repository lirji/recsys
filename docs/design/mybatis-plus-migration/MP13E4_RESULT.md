# MP13e4：成熟 uplift 样本输入

DONE/PASS。5源文件指纹`9d4b143b293f3bbd773765dbbd92e2912875411bec450837bdb86cdb3987f5a8`，清单`/tmp/recsys-mp13e4-source-paths.json`。

GenAdUpliftSamplesJob的单条关联查询迁入广告库UpliftSampleMapper/XML。目标、as-of及可选起始日期绑定参数；EXISTS仍按广告主/用户/目标及分配至到期的闭区间匹配独立转化事实，成熟截止及起始日期同样包含端点，保留assigned_at/assignment_id排序。具名record显式保留原JDBC NULL数值读0、布尔读false与Timestamp→Instant。仍先完整读取，再按用户最后分配时间整用户切分并打开CSV；不新增Repository、事务、池或依赖。

- 旧6真实PG专项、新6+实际四源装配1全部通过；完整离线218项/39类0失败/错误/跳过，全仓24 reactor测试编译通过。日志`/tmp/recsys-mp13e4-{before,after,offline,compile}.log`。
- 覆盖转化双闭区间/微秒越界、广告主及目标隔离、重复事实EXISTS、as-of成熟边界、days精确起点及非正全量、完整CSV字段、重复用户整组切分、NULL/Long.MAX_VALUE。带SQL文本的目标仍作为值处理，不扩大结果或损坏表。
- 空/未成熟输入保留旧文件，缺事实表在打开CSV前失败，非法as-of及输出目录错误沿原异常边界；真实连接立即检查已关闭，PG服务端退出有界观察。输出只在测试临时目录。
- 首次旧测试期望漏掉原seed的user4 signup，修正期望后通过；首次新测试误写SpringSqlSessions包路径导致编译失败，修正为现有spring包后全部通过。未放宽业务断言。
- 45历史SQL及8用户保护摘要不变。hygiene限制仍为未配置统一Java formatter/独立静态分析，不能替代MP15完整验收。

前批12851b30的CI38768505808五门禁全部SUCCESS，FTRL跨架构与CPA跨日测试修正已获远程完整验证。e3=e0a78f1c与本片相邻正常ff合并/push，随后继续GenAdCvrSamplesJob的主库事件/真实分片目录查询。
