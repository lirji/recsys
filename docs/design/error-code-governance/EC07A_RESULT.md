# EC07a 受控前端错误恢复与目录扩展

状态DONE/PASS，本批因真实CI回归提前执行；父EC07仍IN_PROGRESS，其他本地认证/页面提示和最终错误矩阵没有完成。

CI38788884133（9198410f）的自然浏览器验收发现：EC01拒绝任意Error.message时，也隐藏了自然政策校验的受控说明。不是浏览器偶发超时；失败日志`/tmp/recsys-ec02a-ci-browser-failure.log`精确指向natural-exposure.spec.ts的政策不匹配提示。原“未知政策不采集”和原ID恢复不变量保持，必须恢复可行动提示而非放宽测试或恢复任意文本透传。

新增ClientError只接收目录码，toApiError依目录生成`[CODE] 文案`，即使异常message被修改也不采用。自然/混排15个本地校验分支使用稳定码，保留固定安全文案；既有负例强化为必须抛ClientError，防止TypeError冒充业务拒绝。浏览器断言精确要求新错误码和原说明，继续检查无条目采集与恢复原操作。

错误目录由70项扩为88项：本片15个无HTTP状态的前端码，另提前登记后续EC02b需要的AUTH_INVALID_CREDENTIALS、AUTH_TOKEN_ISSUANCE_FAILED、IDENTITY_PROPAGATION_FAILED三个公开码。后续网关提交依赖本片目录，因此先交付本片，再交付EC02b；生成物来自同一版本化目录，不手改Java/TS两份文案。

验证：前端139项/20类全过、typecheck/build、8个受影响TS文件Prettier/ESLint、目录3项Python反例与生成一致性通过。真实浏览器自然5、混排5、广告4均通过，各后端宿主真实PG（广告还含Kafka/对账）1项通过。自然错误截图已目视核对，显示`[NATURAL_DELIVERY_RESPONSE_MISMATCH]`、原安全说明和恢复按钮。旧CI回归已在最终本地浏览器闭环修复；新远程CI需按后续提交SHA核验。

10源路径清单`/tmp/recsys-ec07a-source-paths.json`，指纹`bd5470c513e51d3fa07642b3994e061e9bd94638567d3df6bf9a2d9c0989c283`。测试在包含已验证EC02b改动的联合工作树运行，25源总指纹`9c088cba3be9fa6710cd31aced9445c8f910717b92d2c3adf800451aa46b4a84`；两笔提交相邻交付。前端单测和目录检查直接覆盖本片，联合后端/浏览器证据见EC02B_RESULT，不把联合运行伪称为仅本片提交的独立运行。

Hygiene无阻断；TS格式/ESLint已执行，Java统一formatter/独立静态分析未配置。可整体回退本片及其后续依赖，但会恢复已证实的提示泛化问题；不改鉴权、数据、SQL或曝光收集条件。
