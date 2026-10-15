# EC06a 广告校准与报表未知计数

基线82a4a07a94165e836320c1f0efdf95724b89a7cc（EC05h已交付）。范围AdCalibrateJob、AdReportJob、既有AdSignalsPostgresTest/ExperimentReportPostgresTest/ExperimentReportFixture与公共目录。无SQL/schema/依赖变化，真实pg/Redis/临时子JVM验收，不调用共享库或在线推理。

广告校准复用JobArguments解析model/bins，bins>0在读取/Redis发布前验证；样本不足明确受控失败并保留旧曲线。拒绝非有限pCTR、计算溢出及无法按既有6位发布精度表示的数值，防止Math.round把NaN/Infinity/超范围有限值悄悄变成0或饱和值。正常PAVA公式、等频分箱、NULL预测过滤、distinct点击关联、完整JSON和原两源保持。提取每箱最少5样本的有意义常量；不顺手改概率范围/训练算法或其他校准器。

广告报表无曝光明确失败，since显式缺值不得忽略。countInvalidClicks的实际故障、null或负计数不能当0，必须以专用安全码失败；该读取仍处于原目录创建之后、CSV写入/完整统计摘要之前，空目录可能存在，但不能报完成或风控拦截0。正常计数0、原CSV字段/汇总/分桶/地区格式、正常两份SHA和分桶晚SQL失败的部分CSV保持。

新增可行动目录：广告校准样本不足、校准输入/计算无效、无广告报表数据、无效点击计数未知。未知原始参数/SQL/异常文案不进入公开提示；原内部cause供诊断。

验证：真实PG的NaN/Infinity/超发布精度pCTR和bins0/坏参数不覆盖旧Redis；补正数据后恢复原曲线；既有合法JSON与NULL/dedup边界保持。真实报表正常/无曝光/晚SQL失败基线，以及受控Mapper计数故障/null/负值的子JVM失败与无完成/无误导0摘要；如使用计数替身须明确只验证异常边界、不声称模拟实际数据库网络故障。必要专项、前端生成目录检查、24compile/守卫/hygiene通过后正常Git交付。

本设计在既有EC06授权内冻结，直接实施，不再确认。DataQuality、Attribution/Quality、Ocpc、Multimodal、Bandit/FTRL及EC07/08继续；不把本片当全目标完成。
