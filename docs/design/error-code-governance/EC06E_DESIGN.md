# EC06e 多模态回填的真实结果

基线7a4927168df4260311f9190c7f2343df8cc98ccc，按已批准EC06范围；只改MultimodalBackfillJob、其pgvector测试、错误目录/生成物。不改SQL/向量公式/模型命名/事务/依赖或创建新基础设施。

严格参数0<=alpha<=1、limit>=0，缺海报目录或文本输入、全无可融合物品用专用无输入码。原无海报/NULL文本继续跳过，成功融合计数才触发limit；原零融合向量保持。维度或非有限向量计入失败，继续其他物品后最终部分失败码，不能声称全成功。unsupported在实际失败处停止，提示前面可能已更新，不声明整批未改动。文件/模型/数据库故障以部分失败码传播且保留cause；不新增重试。UPDATE影响行数必须为1才算成功；并发删除不重建、不假计融合成功。

新增JOB_MULTIMODAL_NO_INPUT、JOB_MULTIMODAL_UNSUPPORTED、JOB_MULTIMODAL_PARTIAL_FAILED。真实独占pgvector保护原文件优先级、融合值、部分更新、外层回滚、并发删除，测试无完成/未改动谎报、坏维度/数值/参数、故障与修复恢复。provider使用明确测试替身，不声称真实外部模型验证。必要目录/前端、compile、hygiene后按既有授权Git交付；其余Bandit/FTRL和EC07/08继续。
