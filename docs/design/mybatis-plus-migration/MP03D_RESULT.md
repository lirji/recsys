# MP03d：DIN/DIEN评分序列迁移

本片DONE/PASS；源指纹 `7fe03c185f087ffd4394a160562194e14148e9126b184690819d009d81828d19`。验证后只同步文档。

DIN/DIEN的重复SQL合并到主源RatingSequenceMapper/XML；模型/Redis优先及回填逻辑不变。RatingSequenceRow将可空epoch映射后继续Java向零截断，不能换成PG bigint舍入；ORDER BY仍使用原ts表达式，保留NULL排序。两排序器仍先LIMIT再反转，并把一次序列读取广播给所有候选。

新增测试ONNX读取序列首项及长度作为输出，从公开rank接口验证实际送入模型的顺序/筛选/长度；Mock只用于特征和Redis边界，真实PG执行SQL，实际ONNX会话执行推理。小固件及生成器入库，训练模型不变。

## 验证

- 旧实现6项真实PG+ONNX测试全部通过。`/tmp/recsys-mp03d-before.log`。
- 迁移后rank29项全部通过；加common30及content18，共77通过，1项原有CI环境检查按条件跳过。`/tmp/recsys-mp03d-after.log`。
- 最终宿主11项（序列6+编码契约2+推荐3）全通过，0失败/错误/跳过。`/tmp/recsys-mp03d-hosts.log`。
- 覆盖强评分动作、每用户查询广播、最近20条、正小数秒、NULL及负小数epoch的原回填值。
- 全仓编译/Hygiene见 `/tmp/recsys-mp03d-compile.log`、`/tmp/recsys-mp03d-hygiene.json`；统一formatter/独立静态分析的既有限制保持。
- 历史SQL、外部协议和其他原用户文件不变；根恢复文档只追加当前入口且不stage。

## Git与下一步

MP03c3已正常ff合并/push main：a98fcebab55190e42f5cf0c0395a31bf6363a4d9。MP03整体完成，继续MP04广告目录/多数据源/真实分片验证，再连续完成后续SQL工作包与统一错误码目标。
