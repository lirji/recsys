# EC08 范围与残留审查

审查基线dc0f46c73449d3b399b8d9aafb23dd6d9a1d22de；不以字符串零命中代替公开链路行为验证。完整门禁结果单独写EC08_RESULT。

## 持久层与代码结构

SQL已进入业务模块的Mapper接口与XML，公共PostgreSQL类型处理和会话装配集中recsys-persistence。没有因更换框架拆服务、改变表owner或机械增加ServiceImpl/空Repository接口；复杂查询、行锁、RETURNING、分片、向量和全文检索保留具名XML。实际需要的简单item CRUD使用BaseMapper，数据库迁移脚本继续版本化保留。

守卫覆盖606个生产Java、90个Mapper XML、45个历史SQL。R9剩余JdbcTemplate仅作原连接/手动事务适配，不含业务SQL；与Spring同源会话、隔离级别、锁顺序、批次部分成功和Flink最终jar资源的实际证据见MP系列结果。结构满足本次目标，不需要为偏好再做模块拆分。具体新增查询约定见docs/13-持久层与Mapper规范.md。

## 错误出口分类与证据

| 出口/残留 | 审查结论与依据 |
|---|---|
| Servlet/网关安全链、普通异常 | GlobalExceptionHandler、GatewayErrorResponses使用目录；ResponseStatusException的任意reason不直接进入响应。EC01/02覆盖原状态/字段/头和未知详情消毒 |
| 11个业务局部HTTP异常映射 | 8个显式ErrorFamily保留code/status与原ID查询恢复语义；EC03、EC04资金/自然/混排真实门禁验证 |
| gRPC服务与客户端 | GrpcErrors只发送目录description/受控trailer，保留协议状态及原读重试边界；服务凭据失败不混成用户403。EC04a～d真实TCP/HTTP与不确定ACK测试 |
| 控制台对象内失败 | SystemOps、User360、Metrics、Diagnosis、Alerts与巡检均明确失败/未知/缺数据，不能伪零或健康。Alerts的message来自Diagnosis受控状态/测量结果，不是Throwable.message；Health来自ProbeStatus固定提示 |
| 实验200拒绝 | EC07b已补遗漏：ok:false/code/安全reason；所有写入口坏ACK不假成功、失败刷新真实配置、部分写效果保持，Redis真实故障与页面均验证 |
| offline/streaming | 44 Job审计分属EC05的35和EC06的9；JobRunner最后公开安全失败并中止，保留必要兼容降级、无输入和部分产物语义。Flink启动读取失败明确码。21项Job日志残留为目录化提示、正常SPLIT_TS机器输出及受控巡检越阈值指标 |
| 前端Error/stack/reason | ErrorBoundary、Callback、AB报告与实验已关闭原泄漏；共用解析只显示目录/安全HTTP fallback。8类HTTP恢复、15类早期自然/混排本地错误及本轮15新增码有实际页面/组件证据 |
| 取消/认证 | showApiError跳过取消；OIDC最多续期一次、实际单飞、失败清身份；主体/epoch变化阻止旧回执。EC07a/c及原identity tests；外部OIDC提供方不在本次测试环境 |
| receiptQueue/naturalReceiptQueue内部new Error | validate失败由ReceiptQueueCore捕获折叠为原UNKNOWN，原始message不进入产品；容量异常折叠为storageAvailable=false。保留回执状态/原ID查询与队列重试，不机械改状态机 |
| Provider缺失等编程异常 | 不逐个增业务码，由渲染边界安全兜底；开发诊断仍保留 |
| Embedding内部异常、AdvertiserAuthz含原cause/reason | 只在内部诊断链路存在；公开HTTP/JobRunner消毒。不是要求全仓异常字符串都消失，不能据此改变原回退/重试/授权事务 |
| BeanValidation fields | 保留原字段校验契约和注解消息，主错误码VALIDATION_FAILED；不是任意Throwable详情。当前页面统一异常入口不直接渲染fields内的远端文本 |
| 其他日志/业务reason | 内部日志不在本次公开提示目录范围；推荐解释、审核原因、CloseReason状态和ContentProtoMapper.description是正常业务数据。保留，不误判为错误泄漏 |

复核快照：/tmp/recsys-ec08-error-residuals.txt（168行）、jobs-residuals.txt（21行）、boundaries.txt（94行）；关键结论已固化本表，不依赖/tmp长期存在。公开出口的未来新写法仍需评审，check_public_errors.py只拦已知形式。

## 魔法值与兼容边界

目录code为显式稳定字符串，不使用ordinal；HTTP通用状态/业务码族、诊断与作业状态/来源、重要训练阈值与参数边界按实际语义具名。现有数学比例、UI尺寸及局部循环索引等不为数量指标机械提取。未改变资金算法、幂等ID、数据库约束、schema、事务边界、已有正常返回或部署配置。

当前无需要再调整服务边界的发现。遗留内部诊断日志的全量脱敏、已暂停企业可靠性任务和生产部署均不在本目标，不能将本次局部安全展示验收宣传成系统整体生产验收。
