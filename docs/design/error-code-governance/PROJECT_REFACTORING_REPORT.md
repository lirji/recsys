# 错误码治理重构报告

基线ea5fda51，完整构建/集成/浏览器证据见../mybatis-plus-migration/MP15_RESULT.md。范围与分批实施见PLAN.md。

已发现：通用code/message重复；部分GlobalExceptionHandler透传内部detail/message；局部HTTP/gRPC错误分散；网关默认响应与Servlet结构不一；前端忽略code并展示任意响应体；离线作业存在已实证的假成功/部分发布提示。均在本次目标内处理，不以一次全局替换掩盖遗漏。

行为兼容：既有HTTP/gRPC状态和code、正常业务结果、身份与重试语义保持；内部详情展示、假成功和已证实的大ID失真将有意修复，并在对应片记录CHANGED及证据。数据库/事务/消息/基础设施保持。

EC01完成公共错误目录、Servlet全局处理与前端共用安全展示，71项后端模块测试、138项前端测试、全仓compile等通过，详见EC01_RESULT。框架错误被误判500与任意详情展示已修复；当时安全链及局部业务尚未迁移，后续进展见下段。整体重构IN_PROGRESS，不能把公共层通过等同全项目完成。

EC02a/b安全链与响应式网关已完成本地验收，88项模块测试与真实Netty HTTP、三套真实浏览器通过，见EC02A/B_RESULT。CI发现的本地政策提示被泛化问题已通过提前执行EC07a修复：88项错误目录含前端受控码，139项前端测试通过。该失败证明任意文本防泄漏之外还须保留可行动的受控说明，后续EC07不得只以generic fallback宣称完成。EC03～EC06、EC07其余入口及EC08全量审计仍未完成。

EC03已完成11个局部业务HTTP边界的目录接入，保持各域分类与503恢复语义，预算JSON说明归一为公共文案。生成8个显式ErrorFamily，旧88项代码/前端字典字节不变；基线保护、70项HTTP专项、393资金/76自然、24模块编译均过，见EC03_RESULT。EC04～08其余范围继续，不将HTTP完成当全项目完成。

EC04a完成sandbox gRPC服务端/认证边界，115项共享目录；原状态和资金结果不变，安全描述+trailer为兼容扩展，预算原异常详情和下游描述/metadata透传有意移除。41项专项含五服务真实TCP、398资金、139前端、24模块编译通过，见EC04A_RESULT。其余RPC/客户端/正常响应内错误仍待父EC04后续。

EC04b完成三个sandbox客户端及预算恢复提示，目录127项；保留HTTP旧码/字段，受控wireCode别名贯通后端和前端。内部服务身份失败与真正业务权限拒绝分开；损坏CPM/CPC/曝光响应不再误判用户输入。72项专项、406资金/76自然、149前端、24模块编译和三套真实浏览器全部通过，见EC04B_RESULT。普通RPC/公开对象内错误与后续作业/前端治理仍未完成。

EC04c完成普通广告/内容/用户RPC及四类客户端的安全目录适配，133目录项，保持真实测得的UNKNOWN及其他实际网络状态和重试判定；写入日志不再将未知称未落库。13项基线、83专项、149前端和24模块编译通过，见EC04C_RESULT。旧写fallback造成HTTP伪成功仍须EC04d修复，不能以安全描述替代行为正确。

EC04d修复普通广告/兴趣写fallback的HTTP假成功；新增两项503目录码，总135。正常成功与outcome重复false保持，未增加写重试。真实TCP+AOP+HTTP验证及426资金、151前端、24模块编译通过，见EC04D_RESULT。严格兴趣读取与console对象仍需后续治理。

EC04e1 DONE/PASS（本地）：严格兴趣查询故障从空200改受控503，推荐专用Reader保留冷启动降级；原读重试和普通写语义保持。总136码，User360局部错误也展示code。18基线、56专项/7类、154前端/21文件、24模块compile及目录/SQL/hygiene通过，见EC04E1_DESIGN/RESULT。无新增依赖/SQL/事务；父EC04的console边界及EC05～08继续。EC04d已ff/push e94cdac6，新CI38801955042运行；C的CI38799881069仅build取消、另四门成功。

EC04e2 DONE/PASS（本地）：观测协议失败/部分结果不再当就绪或无流量，健康状态用显式编码枚举和安全URL，诊断失败/缺数据不默认PASS。新增8对象内码，总144项。22基线、最终63专项/6类、154前端/21文件、24compile、目录/SQL/hygiene通过，见EC04E2_DESIGN/RESULT。Hygiene一条第三方Python虚拟环境枚举相似advisory判不适用；Java工具限制保持。父EC04的SystemOps/User360/Item/Report/Alerts及EC05～08继续。E1已ff/push 8d2fedee，CI38802730985运行；D CI38801955042安全/自然成功，钱/浏览器/build取消。

EC04e3 DONE/PASS（本地）：报表保留真实CSV回退，但DB失败无对应文件不再假空/404；告警复用诊断规则，信号缺失可见且不额外读eval。诊断/告警状态、来源为显式code枚举，JSON保持原字符串；总146目录项。101专项/8类、157前端/22文件、24compile、目录/SQL/hygiene通过，见EC04E3_DESIGN/RESULT。两条第三方Python环境枚举相似advisory判不适用，Java工具限制保持。父EC04剩余User360/Item/SystemOps及EC05～08继续；E2 CI38803835991四门成功、build运行。

EC04e4 DONE/PASS（本地）：User360保留成功数据，各失败分区显示未知和安全码；存在性三态，完整游标后发布计数，坏Redis值/超时不伪零。目录148项，原146/8族保持。113后端/10类、163前端/23文件、24compile、目录/SQL/hygiene通过，见EC04E4_DESIGN/RESULT。Java工具限制保持，未新增SQL/事务/重试。父EC04剩余Item、SystemOps/作业状态及EC05～08继续。E3已ff/push3434795d，CI38805179085尚运行；E2四门成功、build取消。

EC04e5 DONE/PASS（本地）：Item故障返回安全503，真实缺失/批量约束及标题fallback保持。目录149；36后端真实PG/HTTP等、164前端、24compile与目录/SQL/hygiene通过，见EC04E5_DESIGN/RESULT。父EC04剩余SystemOps与EC05共同推进。E4已交付d36f184b；后续最终SHA CI待EC08核验。

EC05a DONE/PASS（本地）：作业状态codec、JobRunner预检/失败传播、DAG安全状态与未知结果、SystemOps安全消费和页面已实现，总159码。147后端/16类、168前端/25文件、24compile与目录/SQL/hygiene通过，见EC05A_DESIGN/RESULT。两处边界catch和第三方enum advisory已说明，Java工具限制保持。EC04（a～e5+SystemOps）既定公开边界本地验收完成；EC05其余Job/streaming、EC06～08继续。E5已交付d3f15c62，新片最终CI另核验。
