# EC05d 召回与训练样本的输入和产出提示

基线cb701520cfdeb1d4d410c27754ffa4cfe1edc500，范围Hot/Idf/ItemCf/UserCf/Swing/ColdBanditStats/UserEmbedding与GenSamples/GenSamplesMt/GenSamplesImpr/GenAdCvrSamples/GenAdUpliftSamples十二作业。继续既定EC05，不改SQL/schema/公式/排序/采样/CSV字段/事务/Redis发布顺序。

缺少行为、物品、正反馈、评分、曝光、广告点击、成熟uplift分配或物品向量时，原在变更前直接return的分支改为对应安全目录异常，使DAG明确未完成并保留旧文件/旧投影。用户向量没有命中输入也明确无产出；三类CF有正反馈但不能产出任何倒排时报告未发布。保留部分用户无邻居、缺个别物品向量等实际算法过滤，不把合法过滤统一当基础设施错误。IDF语料非空但没有合格词时仍发布权威空词表；空语料保留旧快照并失败。

GenSamplesImpr已经写出有效全负标签文件时保留成功，以目录警告说明无正反馈而非声称文件失败。广告CVR缺广告物品关联沿用过滤：尚有样本时用目录警告说明限制；全部过滤、仅写出表头时明确没有产出，不能把旧文件已被打开覆写说成未改变。写CSV原异常继续传播至JobRunner，不重复输出任意异常说明。

十二作业的重复整数/小数CLI解析复用JobArguments；新增finite小数解析并拒绝裸/多值/坏字面量。必要语义校验在读取/发布前：Hot topn、CF topk和截断参数为正；Swing alpha非负；样本neg-ratio/seq-len/max-position非负、valid-frac和hard-neg-ratio为[0,1]。保持days/half-life/window等既有非正关闭语义，不新增武断上限、重试或资源框架。Uplift保持绑定objective/用户整体切分与日期边界，显式坏as-of转参数码。

验证复用真实PG/Redis/pgvector和样本子JVMfixture，更新旧吞错断言但保留原数据效果/文件断言；补CF正常/稀疏/无输入和坏参数不发布、全负标签仍成功、CVR全过滤失败/部分过滤警告。全部测试独占组件，不改共享库或用户训练数据。运行完整offline依赖测试、24compile、目录/SQL守卫及生成TS专项，按必要失败修正后再交付。评估/报表/模拟种子余项另继续EC05，EC06发布与计算债务不缩减。
