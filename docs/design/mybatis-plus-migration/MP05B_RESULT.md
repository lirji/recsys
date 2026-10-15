# MP05b：广告预算、创意、保量与相似度

本片DONE/PASS；源指纹 `1afb1f724e8998fc793fb866ea6d20ed44b1dde0619f32fdfd2eca0f4ff51f7f`。MP05整体完成，验证后仅同步文档。

四个服务的SQL全部进入Mapper/XML。预算/创意仍走分片目录，物品向量仍走derived，GD合约仍走原primary；AdPersistenceConfiguration补充primary包的精确绑定。创意保留两次读取和稳定ID顺序、NULL标题；预算NULL仍为0、缺失广告主仍放行；向量一次预读后复用内存余弦，缺失/负相似/零范数/维度不符均按原规则处理。

GD合约改为具名Contract行，替换七个long[]位置索引；创意ID列表去掉单元素long[]。合约时间和价格仍由PG bigint转换后回填0，不改为Java截断。选择算法、Redis消耗及降级逻辑不变。

## 验证

- 旧实现：真实双分片8项、向量4项通过，`/tmp/recsys-mp05b-before.log`；GD最终3项通过，`/tmp/recsys-mp05b-gd-before-final.log`。初稿GD断言遇到double最后一位差异，改为1e-12容差后通过；没有改变生产计算。
- 迁移后32项全部通过，含上述15项、事件7、GD/UCB纯函数10；`/tmp/recsys-mp05b-after.log`。
- 最终联合44项/9类全部通过，0失败/错误/跳过，包含推荐Boot与广告副本；`/tmp/recsys-mp05b-hosts.log`。
- 验证NULL预算为0、两片预算与创意读取、审批过滤、未探索臂按ID优先、向量边界、GD舍入/NULL/无效窗口/缺表降级。没有增加每请求查询次数。
- 全仓24 reactor编译成功，`/tmp/recsys-mp05b-compile.log`。Hygiene无阻塞，`/tmp/recsys-mp05b-hygiene.json`；原formatter/独立Java静态分析缺失限制保持。
- 45历史SQL与8保护文件摘要一致，未访问共享数据库。

## Git与后续

MP05a已正常ff合并/push main：722c85b4。当前片完成后继续MP06行为/自然回执/console，再完成后续全部SQL及统一错误码目标。最终全仓clean verify、资金/自然/browser门禁仍留MP15，不能以当前专项通过代替最终验收。
