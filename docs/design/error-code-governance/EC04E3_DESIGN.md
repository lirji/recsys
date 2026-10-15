# EC04e3 报表不可读与告警信号缺失

基线858ca2304a02621f49e2bfa626466177a0d4b454，继续已授权父EC04。先复用该修订63项console专项基线；其10源指纹及日志见EC04E2_RESULT。当前git核验无其他任务源码，不重复已交付切片。

## 报表边界

保留DB优先、DB无数据/失败时的真实CSV回退及正常字段、排序、NULL单元格、400非法文件名、404确定不存在。目录加载用私有快照记录DB失败原因：CSV确实可用时继续返回，但DB失败且没有对应类别/同名文件时，不能用空/null/404表示已确认不存在，返回CONSOLE_REPORT_UNAVAILABLE(503)。latest只在成功查明该类别没有报表时返回null；读取故障继续传播给诊断。DB异常+别类CSV不能证明查询类别不存在。无DB装配的显式CSV模式保持。

CSV目录/元数据读取故障返回503，不伪造空目录/零字节/零时间；CSV内容解析失败使用CONSOLE_REPORT_READ_FAILED(500)，不暴露路径/SQL/异常原文。旧400/404采用PublicHttpException的既有BAD_REQUEST/NOT_FOUND，保持ResponseStatusException分类；其他失败PublicErrorException保留内部cause，公开响应只取目录。DB坏JSON仍可回退同名有效CSV，null容器/行不能当作有效表。此次不改SQL、schema、事务、报表业务数值或已有文件回退优先级。

CSV回退是现有可用性策略，不能声称它一定比不可读的DB更新；不在本片虚构新鲜度或完整数据库目录保证。公开DTO不增加新必填字段，版本共存仍按原成功结构。

## 告警与结构

AlertsService复用DiagnosisService已经验证的服务/数据质量/延迟规则，移除重复500ms阈值和重复吞错逻辑。提供只检查原三类信号的快照入口，不额外读取eval、不新增监控目标。诊断保留原检查顺序与eval项；告警不新增eval来源。

诊断状态PASS/WARN/FAIL/INFO、告警级别INFO/WARN/ERROR和三类source用显式稳定code枚举；诊断复用E2的ProbeStatus解析健康状态，Jackson仍输出原字符串，不使用ordinal。告警映射FAIL→ERROR、WARN→WARN，原服务被动INFO不参与告警，延迟成功但无观测值可显示INFO；缺报表/观测不可用/探测失败会出现带安全码的WARN，不能空列表伪称健康。排序保持ERROR→WARN→INFO再时间倒序。正常全部有效且无异常时仍空；前端空文案收窄为“当前已取得的信号未发现告警”。越阈值详情复用受控数值/计数，不复制报表任意breach文本。

不增加空接口/透传服务，无新依赖/配置/SQL/存储写入。User360主视图、Item与SystemOps/作业状态后续继续。本片为小范围后端聚合/错误纠正和一句前端文案，不重启中大型前端规划。

## 验证与发布

真实PG扩展：DB表不可用+无文件/别类别文件、坏JSON+无回退、真实同名CSV可恢复、最新类别查询错误传播；已有DB优先/NULL/顺序/可选Mapper/正常CSV保持。临时文件测试解析/目录故障，HTTP验证原400/404与新503/500且不泄漏。告警真实Diagnosis聚合+可控信号测试失败/未知/无观测/恢复、顺序与稳定JSON编码，确认不额外读取eval；前端真实组件验证未知信号错误码和健康文案收窄。全console/相邻HTTP、前端测试/build、全仓compile、目录/持久边界/hygiene。按显式清单正常提交ff/push；无数据库回退，代码回退会恢复旧假空/假健康。
