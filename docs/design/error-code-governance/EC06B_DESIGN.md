# EC06b 归因身份与质量发布失败

基线c6921b8fc5e1a4521c831cca18e56cb743598f26。在已批准EC06范围治理AttributionJob、QualityScoreJob、AdAttributionMapper注释、AdAnalysisPostgresTest/Fixture及错误目录。无SQL/schema/依赖/计费公式/多键事务变化；真实独占PG/Redis与临时CSV验收。

归因事件与折叠触点使用具名类型，广告ID全程long，事件类型和模型使用显式code枚举，去掉double数组的身份转换与数字下标。修复2^53邻近身份合并，保留原NULL身份映射0、原SQL排序与时间窗口、点击优先/同类更近、三模型公式与信用守恒。未知模型仍兼容回退position，但公开提示改固定码、不输出原始输入，大小写使用Locale.ROOT。无事件/无转化明确失败，不生成报表；CSV创建/写入失败专用码，不再打印完成。

两Job复用JobArguments严格解析。归因半衰期正、窗口非负、首末权重非负且总和有限；原first+last>1归一化与0/0权重均分保持。质量最少曝光/先验非负，权重非负且和正有限，q-min非负且q-max不小于q-min，comp-clamp非负；不新增未经业务定义的上限。质量无曝光或全部低于门槛明确无产出，旧键保持（原空CSV头可保留）。

质量CSV/Redis失败专用部分发布码，保留已写键/行，不重试、不假称回滚。非法/无法表示的统计输入或计算结果不能经Math.round变0/饱和值；发布前检验当前广告各输出值和最终质量，发生晚错误时先前广告可已更新，提示必须说明。显式行为变化：旧QUALITY_OPTIONS中的广告3在prior-click=0且clicks=0时CVR为0/0，曾把NaN质量发布成0；现在受控失败且保留其旧键。前两广告原有效行、默认完整CSV和三归因模型完整SHA保持，正常零先验在分母有效时仍可使用。纯数学类QualityScore/MultiTouchAttribution公式不修改。

验证真实PG完整long的2^53邻值/Long.MAX_VALUE/NULL、信用守恒及原五份报表基线的有效部分；真实Redis ACL第二广告失败保留第一广告与CSV，目录为文件时失败、无完成日志、修复后恢复。补非法参数无副作用、无事件/转化/无合格广告、原坏数值伪零回归。必要专项含纯公式/Runner/DAG、目录与前端57项、24compile/守卫/hygiene后正常Git交付。父EC06其余5个Job和EC07/08继续。

本片按既有授权冻结并直接执行，不请求重复确认。
