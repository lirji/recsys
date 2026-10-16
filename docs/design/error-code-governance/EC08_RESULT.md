# EC08 最终验证与交付验收

状态：本地验收 DONE / PASS。产品基线为dc0f46c73449d3b399b8d9aafb23dd6d9a1d22de；本片新增CI浏览器入口、审查和最终文档，没有再修改产品源码。正常Git交付与最终SHA五门CI由根CODEX_PROGRESS.md记录，前序CI不能替代最终提交。

## 验证结果

下列数字顺序为测试总数/失败/错误/跳过；各门禁互有覆盖，不相加冒充独立用例数。报告只采集该命令启动后生成的XML，完整verify日志及退出码同时校验。

| 检查 | 实际结果 |
|---|---|
| 全仓 Maven verify / Surefire | 1348/0/0/4，235类，809秒；24个reactor模块SUCCESS |
| streaming最终shade制品 / Failsafe | 1/0/0/0；独立JVM与真实PG |
| money | 426/0/0/0（Maven宿主）；260秒 |
| natural | 76/0/0/0（Maven宿主）；46秒 |
| browser-ad | 1/0/0/0（Maven宿主）；4项Chromium通过；47秒 |
| browser-natural | 1/0/0/0（Maven宿主）；5项Chromium通过；45秒 |
| browser-feed | 1/0/0/0（Maven宿主）；5项Chromium通过；53秒 |
| 公开错误与巡检组合浏览器 | 11项通过（公开错误9、巡检2） |
| 完整前端 / 最终认证 | 202项/31文件；认证专项13项，通过；类型与build通过，证据EC07B/C_RESULT |
| 真实Redis/HTTP实验写入 | 6项通过，证据EC07B_RESULT |
| 静态守卫 / 模型契约 | ONNX自包含10、Python编码golden10、错误目录10、持久守卫6及生成/公开出口/606Java90XML45历史SQL检查通过 |

全仓4个跳过为3个按需浏览器宿主和1个CI环境探针；前三者已由本片三个真实后端浏览器脚本实际执行。CI探针在本机仍为条件跳过，最终CI的clean verify负责执行，不能宣称本地零跳过。所有本地Maven严格串行，使用独占PG/Redis/Kafka fixture；未写共享dev_infra。

## 完整目标与审查

- MP00～15已将生产SQL迁到业务模块Mapper/XML；公共PG类型/会话集中recsys-persistence。45历史SQL未改，R9 JdbcTemplate仅保留原连接/手动事务适配，无业务SQL。结构结论见EC08_REVIEW与MP15_RESULT。
- EC01～07覆盖公共与局部HTTP、安全链、gRPC服务/客户端、console对象内错误、44个离线Job和streaming、前端错误/认证/取消/失败恢复。256目录项及8个码族保持，具体行为变化、兼容性与逐片证据见各RESULT。
- EC08_REVIEW按公开出口分类核对残留。内部日志/cause、BeanValidation原字段校验契约和正常业务reason不机械改写；源码守卫只拦已知形式，不等于全数据流分析。
- 既有browser CI job新增11项公开错误/巡检恢复并归档截图；未增加依赖和新的流水线job。
- 手工hygiene无阻断；Java统一formatter、独立静态分析未配置，原runner不可发现，如实沿用EC07记录。security-scan为报告型，成功不代表零漏洞。

## Revision fence

1325个已跟踪源码/测试/构建/契约文件（排除8个用户保护文件）指纹：`7a68308c7a8bddd76ddf3052ffb3aea39090a9b4518e8be0b95ae772874568d8`。
算法为SHA256(sorted(path + NUL + fileSHA256 + LF))；本次清单/tmp/recsys-ec08-source-manifest.json。交付前逐文件复核；相对dc0f46c7产品代码不变，CI唯一新增为已本地通过的11浏览器入口。文档不纳入源码指纹。

## 日志摘要

| 日志 | SHA256 |
|---|---|
| /tmp/recsys-ec08-full.log | `3463e773fd5666811311b4237029e2294066979edbcac8b15dc53ab1607463ef` |
| /tmp/recsys-ec08-money.log | `ef6cd7b12d05581f1a7cb90891500ad8d0873fd9d139fd1391fba5133b736204` |
| /tmp/recsys-ec08-natural.log | `3f4603c6514a39f7c22948ae1c00cb1e3877f1a27bd4cab924f4d9a63938ddd1` |
| /tmp/recsys-ec08-browser-ad.log | `c15d91bee2e4a18bfc22dd661baaaca05d64de30e5f9614ac1d1ff03cef6d555` |
| /tmp/recsys-ec08-browser-natural.log | `5825fe0b8cf2f56bfacbb56f5995835408cfb8a5ef57bcca2f8f2fda40e44a5c` |
| /tmp/recsys-ec08-browser-feed.log | `5eca03b159372580e785a040897ee82c37479a6afaf2f65a794e159a9823c807` |
| /tmp/recsys-ec08-browser-ui.log | `695739702b19abc1c1db0f28d1b89f64a9f71295607e77177b28a00c1462df40` |

## 交付与边界

正常提交/ff合并/push main，最终SHA的build、money-chain-gate、natural-exposure-gate、sandbox-exposure-browser-gate、security-scan均需SUCCESS。最终CI结果可落根交接，避免为记录自身SHA反复制造提交。

2026-10-16用户另授权在治理全部交付后部署本机Docker；该运行验收独立记录，不将本地门禁当作部署成功。企业可靠性S13B～S25继续暂停。保留用户配置、数据、未跟踪文件与忽略产物；没有清理或生产部署授权。代码回退采用正常revert，不替代已发生业务效果的补偿。
