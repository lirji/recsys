# EC05g 报表、延迟模型与人群投影

基线56465b388ef4a79ceeffd37224b28ff879e7569f。范围AbReportJob、DelayModelJob、LookalikeJob、AdExploreStatsJob及既有真实PG/pgvector/Redis/子JVM测试；复用JobArguments并增加实际用于人群ID、后续max-ts的严格long解析。SQL、schema、依赖、统计/质心算法、排序、CSV协议与Redis发布顺序保持。

AbReport无满足过滤条件的分桶曝光改为明确失败；alpha/power在(0,1)，拒绝alpha舍入到分位点1的不可计算输入，min-impressions>=0（0的原统计语义保持），CUPED pre-days>=0、min-users>=2，启用CUPED必须提供since。数字/裸参数使用统一解析，缺since专用可行动码；正常原始/CUPED报表摘要、找不到显式baseline时原回退和CUPED真实不足状态保持。日期字符串仍由原PG参数类型处理，不引入不兼容日期格式收缩。

DelayModel min-samples>0、max-delay-days>=0且有限；days<=0原全量语义保持。样本不足、非正/非有限均值或无效lambda（包含发布精度下为0）不得return成功；提示必须说明旧模型保持，不能称下游一定不纠偏。两个Redis键仍按原先后发布，不宣称原子；真实第二键失败证据保留。

Lookalike top-n>=0，0继续发布仅种子并集；audience用完整long解析，不能经过double。空人群失败；缺向量的人群仍保留旧包并继续其他人群，但最后以明确码失败，不能完整成功。缺表/查询/Redis失败沿原传播时机，不新建重试。原重复/NULL/Long.MAX_VALUE身份和扩散排序保持；补齐缺向量后可重跑。

AdExplore真实空事件仍成功发布total=0、保留未出现广告/创意旧键；warning改受控目录且如实说明该局部快照，不能误称旧键已清理。实际SQL/Redis失败仍失败并保留既有部分发布事实。

验证既有完整报表SHA、延迟模型数值、种子扩散集合和真实故障边界；增加无输入/参数/部分缺向量最终失败及恢复、空投影目录提示、旧模型/旧包保护。按当前授权直接实施，不再确认。共享CLI来源在后续EC05h集中治理，EC06～08范围保持。
