# 错误码治理进度

整体IN_PROGRESS。SQL迁移已交付ea5fda51，本阶段分支refactor/unified-error-codes；目标、兼容边界与EC01～EC08见PLAN.md。原企业可靠性任务仍暂停。

EC01已交付ca62a933、EC02a已交付9198410f；SQL最终CI38785419045全绿。9198410f的CI38788884133发现自然浏览器提示回归，已在EC07a修复并本地复验，不能声称旧CI已成功。

EC07a DONE/PASS，已正常ff/push main交付8ff3c2d21eb93d4b086b3bc432f1acb1b94ebd34：ClientError和15类自然/混排校验提示保留code与可行动说明；139项前端、自然5/混排5/广告4真实浏览器及各真实后端宿主全过，见EC07A_RESULT。目录共88项（含EC02b三个码）。父EC07其余范围未完成。

EC02b DONE/PASS，已正常ff/push main交付bb0b318bad9e7e655b61141add688a09c0ca295d，父EC02验收完成：旧4项Bearer/限流保护、新完整后端88项/16类、真实Netty HTTP、全仓compile、目录/持久边界守卫通过，详见EC02B_RESULT。EC03 DONE/PASS，已正常ff/push main交付f2149d7fa9140e29d543ddd55d51752e3cc8b36b：11个局部控制器目录化，8个显式码族与生成器校验；改前资金384/自然75、新兼容测试10项过，改后HTTP专项70、资金393、自然76、全仓24模块compile及Python5项通过。Hygiene无阻断，Java格式化/独立静态分析未配置。详见EC03_RESULT。EC04 IN_PROGRESS；EC05～EC06与EC08 TODO，EC07剩余范围待做。整体错误治理IN_PROGRESS，最新远程CI与实际交付SHA以根交接和Git为准。

EC04a DONE/PASS：五个sandbox gRPC服务与内部认证接入115项目录；只公开目录码/文案和受控trailer，拦截原异常描述与任意metadata。改前23项、改后41项（含五服务真实TCP）、钱398、前端139/构建/格式、全仓24模块compile和目录/SQL守卫全部过，见EC04A_RESULT。Javaformatter/独立静态分析仍未配置。EC04b客户端/其他RPC及其余公开错误待做，父EC04不能标DONE。

EC04b DONE/PASS（本地验证）：三个sandbox客户端、四服务caller码、预算wireCode上下文与前端精确安全选择器；127项目录，原115项保持。内部服务身份失败有意纠正为依赖503，业务拒绝保持403；损坏CPM/CPC/曝光回复保持未知。47项基线、72项专项、406资金、76自然、149前端、24模块编译、广告4/自然5/混排5真实浏览器及目录/SQL守卫全过，详见EC04B_RESULT。24源指纹740d83f1dd96302d6c1b994734518aaab82de822eb71d8236cd13b7cfa052343。Hygiene无阻断，Java工具限制保持。基线bf078299 CI38795249788五门SUCCESS，当前片新提交CI另核验。父EC04其余普通RPC/HTTP/正常对象内错误继续，整体IN_PROGRESS。

EC04c DONE/PASS（本地）：三普通服务8方法与四类客户端8入口目录化，总133码。真实TCP证实旧Runtime异常统一UNKNOWN并保持，避免误触发读重试；正常ACK/重复false及fallback保持，后续EC04d修复HTTP假成功。13项基线、83项专项、149前端、24模块编译、生成与SQL守卫通过；Hygiene无阻断、Java工具限制保持。见EC04C_RESULT。EC04b已正常ff/push ba09172f，新CI38798495615尚运行，非最终全绿。

EC04d DONE/PASS：普通广告/兴趣写入的RPC失败、丢应答、熔断及非成功ACK返回受控503；正常outcome重复false保持，无写重试。总135码。44旧专项、8旧HTTP刻画、69新专项、426资金/74类、151前端及build、24模块compile、目录/SQL守卫通过。Hygiene无阻断，Java工具限制保持。见EC04D_DESIGN/RESULT。EC04c 1a61da1b已ff/push，CI38799881069四门SUCCESS、build仍运行；EC04b CI38798495615终态CANCELLED（钱与build取消，另外三门成功），不称五门通过。父EC04e及EC05～08继续。

EC04e1 DONE/PASS（本地）：严格兴趣查询故障从空200改受控503，推荐专用Reader保留冷启动降级；原读重试和普通写语义保持。总136码，User360局部错误也展示code。18基线、56专项/7类、154前端/21文件、24模块compile及目录/SQL/hygiene通过，见EC04E1_DESIGN/RESULT。无新增依赖/SQL/事务；父EC04的console边界及EC05～08继续。EC04d已ff/push e94cdac6，新CI38801955042运行；C的CI38799881069仅build取消、另四门成功。

EC04e2 DONE/PASS（本地）：观测协议失败/部分结果不再当就绪或无流量，健康状态用显式编码枚举和安全URL，诊断失败/缺数据不默认PASS。新增8对象内码，总144项。22基线、最终63专项/6类、154前端/21文件、24compile、目录/SQL/hygiene通过，见EC04E2_DESIGN/RESULT。Hygiene一条第三方Python虚拟环境枚举相似advisory判不适用；Java工具限制保持。父EC04的SystemOps/User360/Item/Report/Alerts及EC05～08继续。E1已ff/push 8d2fedee，CI38802730985运行；D CI38801955042安全/自然成功，钱/浏览器/build取消。

EC04e3 DONE/PASS（本地）：报表保留真实CSV回退，但DB失败无对应文件不再假空/404；告警复用诊断规则，信号缺失可见且不额外读eval。诊断/告警状态、来源为显式code枚举，JSON保持原字符串；总146目录项。101专项/8类、157前端/22文件、24compile、目录/SQL/hygiene通过，见EC04E3_DESIGN/RESULT。两条第三方Python环境枚举相似advisory判不适用，Java工具限制保持。父EC04剩余User360/Item/SystemOps及EC05～08继续；E2 CI38803835991四门成功、build运行。

EC04e4 DONE/PASS（本地）：User360保留成功数据，各失败分区显示未知和安全码；存在性三态，完整游标后发布计数，坏Redis值/超时不伪零。目录148项，原146/8族保持。113后端/10类、163前端/23文件、24compile、目录/SQL/hygiene通过，见EC04E4_DESIGN/RESULT。Java工具限制保持，未新增SQL/事务/重试。父EC04剩余Item、SystemOps/作业状态及EC05～08继续。E3已ff/push3434795d，CI38805179085尚运行；E2四门成功、build取消。

EC04e5 DONE/PASS（本地）：Item故障返回安全503，真实缺失/批量约束及标题fallback保持。目录149；36后端真实PG/HTTP等、164前端、24compile与目录/SQL/hygiene通过，见EC04E5_DESIGN/RESULT。父EC04剩余SystemOps与EC05共同推进。E4已交付d36f184b；后续最终SHA CI待EC08核验。

EC05a DONE/PASS（本地）：作业状态codec、JobRunner预检/失败传播、DAG安全状态与未知结果、SystemOps安全消费和页面已实现，总159码。147后端/16类、168前端/25文件、24compile与目录/SQL/hygiene通过，见EC05A_DESIGN/RESULT。两处边界catch和第三方enum advisory已说明，Java工具限制保持。EC04（a～e5+SystemOps）既定公开边界本地验收完成；EC05其余Job/streaming、EC06～08继续。E5已交付d3f15c62，新片最终CI另核验。

EC05b DONE/PASS（本地）：Flink类目不可读不再健康启动；向量/报表部分失败最终受控异常、成功部分保留并支持修复续跑。166码，43后端+streaming最终jar6+1、24compile、Python7、前端57专项/格式/ESLint、目录/SQL/hygiene通过，见EC05B_DESIGN/RESULT。生成器沿现有100列规范折行，无新依赖。父EC05其余Job、EC06～08继续；A已交付7bd314fd，新CI仍需最终SHA核验。

EC05c DONE/PASS（本地）：五导入Job必需输入/坏参数/部分完成及R9校验目录化，JobArguments复用；合法semantic训练header、原空文件清除/批次/事务与可选ratings明确fallback保持。175码原166/8族不变；65后端/10类、前端57专项、Python7、24compile及目录/SQL/hygiene通过，详见EC05C_DESIGN/RESULT。Java工具限制保持。B已ff/push f09ccef8，CI四门成功、build仍运行。EC05其余业务Job及EC06～08继续。

EC05d DONE/PASS（本地）：十二召回/样本Job无输入/无产出明确码，合法空词表/全负标签与部分关联缺失保持有效输出并受控提示；数字参数复用并前置拒绝。目录189，原175/8族保持；完整offline及依赖453项/83类0失败错误、1项CI环境探针skip（实际PG等均运行），前端57、24compile和目录/SQL/hygiene通过。见EC05D_DESIGN/RESULT。C已ff/push cb701520、CI四门成功/build仍运行；父EC05余项及EC06～08继续。

EC05e DONE/PASS（本地）：种子/模拟参数先于清理校验，清理部分失败阻止生成；创意读取失败拒绝写事件，真实空创意保留NULL归因并受控提示。目录194原189/8族保持；55后端/7类含真实PG FK与Redis ACL故障及恢复、前端57、24compile和目录/SQL/hygiene通过。见EC05E_DESIGN/RESULT。D已ff/push bc781867，CI38811445623仍运行；EC05评估/报表/投影/共享CLI及EC06～08继续。

EC05f DONE/PASS（本地）：三评估Job部分失败最终受控失败，正常/原部分CSV与曲线完整摘要保持；非法参数先于发布，缺head与空候选区分，Eval中断和Error不吞。目录202原194/8族保持；41后端/5类、57前端、24compile与目录/SQL/hygiene通过，详见EC05F_DESIGN/RESULT。E已ff/push b8638f28，CI38812115409四门SUCCESS/build运行；EC05报表/投影/共享CLI及EC06～08继续。

EC05g DONE/PASS（本地）：报表空输入/非法统计参数、延迟样本不足/无效模型、人群缺向量最终失败明确码；合法空探索快照与仅种子包保持。209目录旧202/8族保持；84后端/10类（83首轮+AdSignals最终13替代12）、57前端、24compile与守卫/hygiene过。见EC05G_DESIGN/RESULT。F已ff/push 56465b38，CI38813384975运行；E CI已取消。EC05h共享来源参数后审计剩余作业，EC06～08继续。

EC05h DONE/PASS（本地），父EC05本地验收完成：三共享来源与max-ts安全参数、三Sync校验先于DDL/full清除、Uplift严格参数且0 no-op保持。35个EC05 Job与9个待EC06 Job的44项清单见EC05H_RESULT。212目录旧209/8族保持；完整offline依赖477/84类0失败错误、1个CI环境探针skip（实际集成运行），57FE/24compile/守卫/hygiene过。G已ff/push2717c956，CI38814199492运行，F/E已取消。EC06～08继续。
