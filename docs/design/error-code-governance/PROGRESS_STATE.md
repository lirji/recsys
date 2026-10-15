# 错误码治理进度

整体IN_PROGRESS。SQL迁移已交付ea5fda51，本阶段分支refactor/unified-error-codes；目标、兼容边界与EC01～EC08见PLAN.md。原企业可靠性任务仍暂停。

EC01 DONE/PASS，已正常提交/ff/push main：ca62a933de420d9257ce89ecc918d28affed0124。共享70项错误目录、Java/TS生成/CI防漂移、Servlet全局安全处理、前端共用[CODE]展示。71项后端模块测试、138项前端、全仓compile、目录与持久边界检查全部通过。证据见EC01_RESULT。SQL最终CI38785419045五项全部SUCCESS；EC01 CI38788407786当前运行中，不能继承SQL的成功。

EC02 IN_PROGRESS：EC02a Servlet安全链本地DONE/PASS，72项模块测试与全仓compile通过，见EC02A_RESULT；EC02b Reactive网关READY，具体边界见EC02_DESIGN。EC03～EC08 TODO。下一步交付EC02a并实现网关错误体、Bearer语义与限流空体处理；不会因公共入口已实现而宣称全部错误治理完成。
