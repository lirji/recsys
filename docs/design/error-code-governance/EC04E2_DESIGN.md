# EC04e2 观测查询与诊断错误

基线8d2fedee7155633c161596fa518b73ba333fe4e2；父EC04持续执行。范围为SystemMetricsService、SystemHealthService、DiagnosisService及对应测试/目录。SystemOps作业原始详情、用户360主视图、Item、Report与Alert后续继续；离线巡检的跳过/失败伪成功仍由EC06治理，不在本片宣称完成。

## 决定

Prometheus非success响应、缺少data/result、非预期结构、无效数值目前返回null并被称可达/无流量；查询异常还透传rootMessage。改为统一CONSOLE_METRICS_UNAVAILABLE安全提示及available=false，数值全部null；有效空vector、NaN/Inf保留可用但无值，改说明为“当前窗口没有可用推荐指标”，不推断无流量。现有配置要求聚合结果为0或1条vector，其他类型/多条结果不能任取第一条，延迟/QPS负数也不能算有效观测；非空warnings可能含部分数据，当前视图无部分结果字段，显式不可用；查询/单位/精度/超时保持，关闭指标仍不外呼。

依据[Prometheus官方HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/)：success/error envelope、resultType及vector的value结构。仅核验协议，无依赖/服务版本变更。新检查明确区分协议错误与成功空集，旧自定义非聚合PromQL若返回多条/非vector将显式不可用，不静默取第一条。

健康探测缺配置、不可达、HTTP错误、非法/缺失status使用受控目录提示，明确DOWN/OUT_OF_SERVICE也有健康失败码；主动探测状态采用稳定code枚举，远端status只接受UP/DOWN/OUT_OF_SERVICE/UNKNOWN，其余归UNKNOWN，不显示任意文本。被动目标仍保持配置语义；返回URL去除userinfo/query/fragment，避免凭据进入页面，实际探测目标不改。正常状态与HTTP失败DOWN保持。

诊断四类catch统一安全码，未知不拼异常文本；健康列表为空不能整体当全部健康。指标不可用记WARN，成功无指标为INFO。数据质量报表的四项既有数值（breaches/item/user coverage/ECE）缺失、非有限、非法范围不能用0/1默认PASS；仅已取得有效值才能按原阈值判定。breach详情不再复制报表任意文本，保留安全计数及按数值生成的覆盖/ECE说明；报表原文读取功能不变。新码均httpStatus=0，因为这些是原HTTP200的诊断对象内错误状态；保持响应字段和页面布局。

## 验证与回退

运行console原PG/系统基线，保留健康UP/DOWN/被动/缺配置、有效指标/真空/禁用、单位精度；通过真实本地HTTP可控响应验证非法/错误/断连、健康状态与URL安全，诊断Mock仅验证聚合决策并注明边界，不冒充真实Prometheus。控制器HTTP返回原结构，错误含code且无任意详情。前端目录生成/check和构建、全仓compile、hygiene。无SQL/事务/存储副作用；回退代码会恢复旧未知当空与详情泄漏，未改数据库或基础设施。
