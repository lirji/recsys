# EC06d oCPC 校准错误与回退

基线6f5c1b8f227d42c040cdc418c2765aaf32fbb6c9；在已批准 EC06 范围继续。仅 OcpcCalibrateJob、其真实 PG/Redis 测试、错误目录及生成物；无 SQL/公式/schema/依赖/在线出价行为变化。

保留正常目标 CPA 均值、主源/双分片聚合、完整 long 身份、延迟权重、clamp、四位小数发布与逐广告主写入边界；无花费/转化保持原系数仍是合法完成。days<=0 实际保留 epoch 下界，修正文档误称全历史。缺失旧 k 仍从 1 开始；缺失 λ 仍使用原始计数，但必须固定错误码提示降级。显式 --delay-correct 裸开关仍 true；其他数值/布尔拼错严格拒绝，alpha>=0、0<min<=max、0<min-completion<=1，无业务依据不增加上限。

损坏/不可读旧 k 或 λ 不得静默当缺失而覆盖旧值；无 OCPC 目标明确无输入失败。非有限或非法目标/花费/转换统计、计算溢出、四位舍入零或饱和在当前发布前拒绝。前面已写的广告主可能生效，提示明确部分更新可能存在；不新增重试、不声称多键回滚。依赖读写异常有安全错误码并保留内部 cause，不公开原始 Redis/SQL/模型内容。

目录新增 JOB_NO_OCPC_TARGET、JOB_OCPC_DELAY_MISSING、JOB_OCPC_INVALID_DATA、JOB_OCPC_FAILED；旧码不变。真实独占双 PG/ShardingSphere/Redis 验证原 9 项边界，扩展坏参数、损坏模型、ACL 读/写故障、数值与恢复。纯公式不修改，必要前端目录/生成/SQL 守卫与编译/hygiene 后正常交付。父 EC06 其余三 Job、EC07/08 继续。按已有持续授权冻结执行。

验证补充：ShardingSphere 的 AVG 合并用 BigDecimal，NaN/Infinity 目标在 Mapper 回调前即抛 NumberFormatException，应返回 JOB_OCPC_FAILED；测试精确核对该 cause 与旧值保留。回调内已识别的非法花费等保留 JOB_OCPC_INVALID_DATA，不改变驱动或 SQL 以迎合分类。
