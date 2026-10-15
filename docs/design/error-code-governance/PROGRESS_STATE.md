# 错误码治理进度

整体IN_PROGRESS。SQL迁移已交付ea5fda51，本阶段分支refactor/unified-error-codes；目标、兼容边界与EC01～EC08见PLAN.md。原企业可靠性任务仍暂停。

EC01已交付ca62a933、EC02a已交付9198410f；SQL最终CI38785419045全绿。9198410f的CI38788884133发现自然浏览器提示回归，已在EC07a修复并本地复验，不能声称旧CI已成功。

EC07a DONE/PASS（待本地提交）：ClientError和15类自然/混排校验提示保留code与可行动说明；139项前端、自然5/混排5/广告4真实浏览器及各真实后端宿主全过，见EC07A_RESULT。目录共88项（含EC02b三个码）。父EC07其余范围未完成。

EC02b本地验证通过，父EC02仍待本批Git交付收尾：旧4项Bearer/限流保护、新完整后端88项/16类、真实Netty HTTP、全仓compile、目录/持久边界守卫通过。接下来先提交EC07a目录与前端修复，再提交EC02b网关适配，正常ff/push后进入EC03。EC03～EC06与EC08仍TODO，整体错误治理IN_PROGRESS。
