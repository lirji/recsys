# EC03：业务 HTTP 错误目录接入

基线 bb0b318bad9e7e655b61141add688a09c0ca295d；沿用已批准 PLAN 的 EC03 范围。SQL 与资金状态机、事务、表结构均不变。

## 决策与范围

11 个局部处理器中，10 个使用 8 个既有码族，预算控制器只单独处理非法 JSON。选择在 contracts/errors.json 增加显式 family 元数据，生成纯 Java ErrorFamily，ApiError 提供码族工厂。HTTP 状态分类继续留在各控制器，避免统一分类器丢失领域差异；例如 CPM 交付目前并不单独识别 InvalidProof，CPC/CPA 交付识别它，这一行为不借文案治理修改。

ErrorFamily 使用显式 prefix 与目录内 fallback 引用；已登记状态直接复用 ErrorCode 文案，未登记的错误状态（如 429）保留旧 FAMILY_429 字符串及该族原安全拒绝文案。仅接受 HTTP 错误状态 400～599。保留 503 原 ID 查询/重试说明，不输出 exception.message。生成器检查 family 名称/前缀唯一性、fallback 引用、成员状态后缀及 HTTP 状态一致性；CI 原 --check 同时保护新生成文件。

预算非法 JSON 保留 BAD_REQUEST/400 与响应字段，文案从“预算命令 JSON 格式非法”归一为目录 BAD_REQUEST 提示；这是明确的文案变更，前端自 EC01 起已使用此目录文案。预算版本冲突等 409 CommandView 和已持久化的 422 业务结果保持原对象，不变为 ApiError。

本片不新增 PublicErrorException 抛出点；已有域异常分类不变。EC04 若迁移抛出类型，必须同步局部状态分派并测试，不能把已知 400 误降为 503。本片不提前修改 RPC 或业务校验。

## 执行与验收

1. 串行完成改前 money-chain / natural-exposure；增加 10 个处理器的兼容契约测试，在旧实现上验证状态、代码、文案、路径和脱敏。
2. 增加目录元数据/生成器/纯 Java 类型/ApiError 重载；替换 11 个处理器的硬编码码与文案、3 个交付处理器的 HTTP 数值，其他分支保持。
3. 验证非法目录/生成物漂移、八族 503 和未登记错误状态、预算文案归一；既有真实 PG 资金/自然链路及实际 MVC 测试串行复验。既有 202/409/422 业务语义和跨主体拒绝必须通过。
4. diff/持久层守卫/hygiene、文档和独立逻辑提交；按授权正常 ff/push，核验该 SHA 的 CI。

不新增运行依赖；生成 TS 字典应字节不变。回退可整体 revert 本片代码/目录生成器/测试，不涉及数据回滚。兼容状态：响应字段/已有码/真实错误状态/503恢复提示 UNCHANGED；预算 JSON 文案 CHANGED；成功或重定向状态被误用为异常将被防守拒绝，实际业务没有该调用。
