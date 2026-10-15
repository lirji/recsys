# 错误码治理重构报告

基线ea5fda51，完整构建/集成/浏览器证据见../mybatis-plus-migration/MP15_RESULT.md。范围与分批实施见PLAN.md。

已发现：通用code/message重复；部分GlobalExceptionHandler透传内部detail/message；局部HTTP/gRPC错误分散；网关默认响应与Servlet结构不一；前端忽略code并展示任意响应体；离线作业存在已实证的假成功/部分发布提示。均在本次目标内处理，不以一次全局替换掩盖遗漏。

行为兼容：既有HTTP/gRPC状态和code、正常业务结果、身份与重试语义保持；内部详情展示、假成功和已证实的大ID失真将有意修复，并在对应片记录CHANGED及证据。数据库/事务/消息/基础设施保持。

EC01已完成公共错误目录、Servlet全局处理与前端共用安全展示，71项后端模块测试、138项前端测试、全仓compile等通过，详见EC01_RESULT。框架错误被误判500与任意详情展示已修复；局部业务、安全链、RPC、作业及剩余前端入口尚未全部迁移。整体重构IN_PROGRESS，不能把公共层通过等同全项目完成。
