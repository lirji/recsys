# MP13b1：行为CSV导入

DONE/PASS。4源文件指纹`78c4ed9aec461c6f854e70febe6b288ad0c200184c33ed23232d8f99ca73bb14`；清单`/tmp/recsys-mp13b1-source-paths.json`。

ImportBehaviorJob的来源清理、批量插入和总数查询进入BehaviorImportMapper/XML；具名Rating替代Object数组，固定RATING/NULL bucket与CSV秒时间转换保持。原1000条批界不变，单批VALUES通过真实PG验证与原JDBC批次同样整批失败；前面已提交的批次不回滚，外层事务存在时全部跟随原DataSource事务。未改变文件缺失、坏格式跳过、重复行、重跑清理自身scene或原日志语义。

旧实现保护测试4项通过（`/tmp/recsys-mp13b1-before-final.log`），覆盖重复执行/NULL/其他scene、文件缺失、第二批约束失败与外层事务回滚。首次测试中旧记录的userId与新首批数据重合，已把专用旧记录ID改为9800后在旧实现重跑，不修改产品逻辑或放宽批次断言。迁移后全离线72项/16类全部通过，0失败/错误/跳过（`/tmp/recsys-mp13b1-after.log`）；全仓24 reactor测试编译通过（`/tmp/recsys-mp13b1-compile.log`）。hygiene无阻塞，统一Java formatter/独立静态分析未配置的既有限制保持（`/tmp/recsys-mp13b1-hygiene.json`）。

45历史SQL与8保护文件不变。测试仅独占PG与临时CSV，未重灌共享库或用户数据。下一片按原计划继续行为读仓同步；ImportItems本身已委托ContentService，不含独立业务SQL，终审核验实际路径即可。
