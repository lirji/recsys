# 错误码治理进度

整体IN_PROGRESS。SQL迁移已交付ea5fda51，本阶段分支refactor/unified-error-codes；目标、兼容边界与EC01～EC08见PLAN.md。原企业可靠性任务仍暂停。

EC01已交付ca62a933、EC02a已交付9198410f；SQL最终CI38785419045全绿。9198410f的CI38788884133发现自然浏览器提示回归，已在EC07a修复并本地复验，不能声称旧CI已成功。

EC07a DONE/PASS，已正常ff/push main交付8ff3c2d21eb93d4b086b3bc432f1acb1b94ebd34：ClientError和15类自然/混排校验提示保留code与可行动说明；139项前端、自然5/混排5/广告4真实浏览器及各真实后端宿主全过，见EC07A_RESULT。目录共88项（含EC02b三个码）。父EC07其余范围未完成。

EC02b DONE/PASS，已正常ff/push main交付bb0b318bad9e7e655b61141add688a09c0ca295d，父EC02验收完成：旧4项Bearer/限流保护、新完整后端88项/16类、真实Netty HTTP、全仓compile、目录/持久边界守卫通过，详见EC02B_RESULT。EC03 DONE/PASS：11个局部控制器目录化，8个显式码族与生成器校验；改前资金384/自然75、新兼容测试10项过，改后HTTP专项70、资金393、自然76、全仓24模块compile及Python5项通过。Hygiene无阻断，Java格式化/独立静态分析未配置。详见EC03_RESULT。EC04 READY；EC05～EC06与EC08 TODO，EC07剩余范围待做。整体错误治理IN_PROGRESS，最新远程CI与实际交付SHA以根交接和Git为准。
