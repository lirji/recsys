# MP03c3：语义ID与TIGER迁移

本片DONE/PASS；源指纹 `fa7ad1babdf41beb5b40c659a0be7bfe8c931a9e514129b427e4aac0abde2a15`，验证后仅同步文档。

SemanticIdRecaller/TigerRecaller 的语义码查询迁到派生源SemanticCodeMapper/XML；前者种子复用RecentPositiveItemsSource，后者主源行为保留每条正反馈事件，不能和MIND去重序列混用。数组以bigint[]/integer[]类型绑定。NULL码仍映射零，原列的过滤保持。

粗簇候选使用类型明确的ResultHandler逐行评分，避免新增全量结果List；生成后的三级/两级/一级回退顺序及每次LIMIT 20保持。新增小型动态形状ONNX测试固件及schema，只验证数据库/推理协议集成；不改生产模型。

## 验证

- 旧实现5项真实PG保护测试全部通过；包含三级回退、NULL码、低评分历史差异、种子排除和空预取。`/tmp/recsys-mp03c3-before.log`。
- 迁移后模块51项全部通过。`/tmp/recsys-mp03c3-after.log`。
- 最终宿主17项全部通过：语义5+固定模型3+向量4+R9 2+推荐3，0失败/错误/跳过。`/tmp/recsys-mp03c3-hosts.log`。
- 主schema只建行为表，派生schema只建码表，误路由会使端到端结果断言失败。
- 全仓编译/Hygiene见 `/tmp/recsys-mp03c3-compile.log`、`/tmp/recsys-mp03c3-hygiene.json`；统一formatter/独立静态分析的既有限制保持。
- 召回生产Java扫描已无JdbcTemplate/JDBC调用/SQL文本；原12个召回SQL候选文件全部核销。
- 历史SQL未修改；原用户文件除根恢复文档追加当前任务入口外均保持。根文档旧交接字节完整保留且不stage，避免夹带原任务内容。

## Git与后续

MP03c2已正常ff合并/push main：1fd57823258c1bf9fde289b5107dd0d0dd5fb146。继续MP03d的DIN/DIEN序列查询，之后MP04～MP15；SQL全量完成后继续统一错误码目标。
