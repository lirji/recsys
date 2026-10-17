# 桶对比演示数据验证

2026-10-17，本机 `desktop-linux`，应用源码 `a25e511d`。

| 检查 | 实际结果 |
| --- | --- |
| 真实行为 HTTP 写入 | 100 条 CLICK，全部关联已有曝光，原曝光内容未变 |
| 初始化重复执行 | 新发送 0 条，现有演示点击仍为 100 条 |
| 真实 AbReportJob + PublishReportsJob | 生成并发布 1 份 DEMO 报表，4 个桶 |
| 报表重复执行 | 复用已发布报表，未再生成/发布新文件 |
| DB 与 BFF | 归因一致；报表索引与内容 API 均通过 |
| 浏览器 `/bucket-board` | 显示 DEMO 文件、图表和4桶明细，浏览器无错误 |
| 清理 | 默认预览只匹配本次100点击和1份DEMO报表；未执行真实清理 |
| 工具静态检查 | Python 编译、Shell 语法、Java 21 启动器编译通过 |

| 桶 | 曝光 | 合成点击 | CTR |
| --- | ---: | ---: | ---: |
| cold;recall:base;rank:onnx;rerank:mmr | 505 | 55 | 10.891% |
| recall:base;rank:onnx;rerank:mmr | 150 | 20 | 13.333% |
| recall:base;rank:din;rerank:diversity | 70 | 15 | 21.429% |
| recall:base;rank:onnx;rerank:diversity | 70 | 10 | 14.286% |

总计795曝光、100合成点击，整体CTR约12.58%。四桶各只有一个用户；页面中的显著性与提升值属于合成演示结果，不能当作真实实验收益。

普通 `OfflineApplication` CLI 在无Web上下文中扫描Web安全链而启动失败，已记录。测试数据工具使用窄启动器调用原正式作业/Mapper完成本次任务；未修改产品代码或在线安全配置。证据保存在本机 `.dev/test-data/ab-board-v1/`，包括 init、幂等、作业、清理预览与 API 验证日志。

## 补充用户样本（2026-10-17，v2）

`--scenario more-users` 实际新增4个标记用户、80条曝光fixture、20条经真实HTTP归因的点击；新增行为合计100条。正式作业已生成并发布 `ab-report-DEMO-20261017-050343.csv`，DB/BFF和浏览器均显示最新4桶明细，浏览器无错误。

| 桶 | 曝光 | 点击 | 每桶用户数 |
| --- | ---: | ---: | ---: |
| cold;recall:base;rank:onnx;rerank:mmr | 525 | 59 | 2 |
| recall:base;rank:onnx;rerank:mmr | 170 | 26 | 2 |
| recall:base;rank:din;rerank:diversity | 90 | 22 | 2 |
| recall:base;rank:onnx;rerank:diversity | 90 | 13 | 2 |

总计875曝光、120点击、5个不同用户（原用户跨4桶，因此每桶用户数不能相加），整体CTR约13.71%。新增用户固定属于单一桶；这些仍是合成演示数据。

最终脚本重复 init 新发送0点击，80曝光/4用户均未重复；重复 report 复用同一文件。默认 cleanup 预览精确匹配本批次80曝光、20点击、4用户、1报表，未执行删除。v1场景回归通过；原895条行为和1份报表逐行一致，11个用户工作区文件指纹不变。Python编译、Shell语法、diff检查通过。证据 `.dev/test-data/ab-board-v2/`。

旧工具提交d340872a的远程CI已终止：8项成功，build中的既有 `CatalogApplicationTest.actualPostgresStopFailsClosedAndSameRequestCompletesAfterSameContainerRestarts` 实测9.241秒，超过8秒断言。此限制不计作本地数据验证通过，也未借本任务修改该产品测试；新提交CI另行记录。
