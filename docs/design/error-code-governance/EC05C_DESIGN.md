# EC05c 导入作业输入与部分完成

基线f09ccef8d7cbccd4dd82c9120b1f6af7e822cbda，沿既定EC05继续。范围ImportBehavior、ImportItems、ImportTower、ImportSemanticId、ImportR9Embeddings及真实契约测试，复用JobRunner/DAG安全出口。不改SQL/schema/批次大小/事务传播，不运行真实下载或模型训练。

缺少必需输入统一JOB_INPUT_UNAVAILABLE并在原检查点拒绝，不再return成功。Tower/SemanticId与Behavior保留已存在空文件的原行为（可清空原导入来源/投影），保留500/1000批次、重复覆盖/重复行、额外列及真实晚批失败边界；脏行仍按原宽松规则继续，但完成后有bad计数就抛JOB_IMPORT_PARTIAL_FAILED，已写部分不撤销。Behavior数字/时间解析错误使用JOB_INPUT_INVALID，保持原及时终止，不改数据库约束拒绝行为。正常semantic-id首个非空行的精确item_id,c0,c1,c2表头是训练脚本真实输出，显式识别不算脏行；Tower训练产物确实没有header，不默认跳过未知首行。

R9继续全量文件验证后才发布，原三组手动事务不动。缺必需组/空组、格式/维度/有限值/范数、空版本/重复ID、版本不一致使用明确目录；require-all=false仍允许有输入组发布并保留未提供组，但全部缺失/空时报告未发布而非成功。require-all仅接受true/false，防拼错隐式降级为false；原向量维度64、范数平方容忍区间0.81～1.21提取为具名边界。

ImportItems的ratings.csv本来可选：确实不存在时保留明确的0热度fallback，日志带JOB_POPULARITY_FALLBACK；若文件存在但读取/内容错误则在写物品前失败，不发布半份热度计数。movies短行继续并最终报告部分导入；完整成功与CSV标题逗号/引号行为保持。下载/解压路径不在测试中触发，不更改原策略。

四处重复CLI解析收敛为当前offline模块内JobArguments（text/integer/bool及readableInput），用于本片实际调用点；只做已有参数的严格解析和安全错误码，不引入通用配置框架。坏参数先于有副作用的清表/发布（Tower dim仅要求正数，现有可用三维表仍支持，不把DDL固定64误说成所有表都64）。正常默认值和接口名保持。

验证：现有PG/pgvector各批次/事务/源隔离基线复跑，新增缺输入拒绝、坏参数无副作用、完整header成功、脏行部分效果、R9预校验无发布/可选组与全部无输入、恢复重跑；ImportItems用临时真实文件和受控内容写接口，不访问下载地址。目录7项守卫/生成TS检查、24compile、hygiene和显式Git交付；父EC05其他作业与EC06～08仍继续。
