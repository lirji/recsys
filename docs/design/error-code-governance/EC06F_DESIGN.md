# EC06f Bandit/FTRL 模型发布结果

基线aaa3b01ac40394c31b887b7048908b58b320a1b1；已批准EC06最后两作业作为同一模型发布片。范围两Job、各自真实PG/Redis测试、目录/生成物；不改SQL、数学模型、FeatureAssembler、随机训练顺序、Redis键和JSON契约，不引入事务/重试/依赖。

发布失败必须抛安全目录码，停止“已写”完成日志。FTRL先weights后state边界保持；第二次SET失败时第一键可能已变，提示核对两键，不假回滚。warm-start缺失/坏JSON/契约不符沿用原从零策略但固定码提示；Redis读取失败在解析回退之外传播，不能伪装缺失。Bandit完整特征顺序/矩阵形状/有限数/lambda/n需有效；FTRL坐标必须真实数值且n非负，解析完整后才loadState，禁止缺字段自动asDouble=0。模型产出有限性在发布前验证，异常训练不覆盖旧键。

参数复用JobArguments；Bandit lambda>0；FTRL alpha>0、beta/l1/l2/neg/epochs非负；min-rating仅需有限，seed保留int范围，incremental保留原选项存在即启用。FTRL原epochs=0发布空模型保持，明确非训练失败；100正样本阈值提取具名常量。Bandit空曝光、FTRL空物品/少于100样本明确无产出失败。

新增两作业各NO_INPUT/MODEL_FALLBACK/MODEL_INVALID/FAILED共8码。真实独占PG/Redis验证旧Bandit完整矩阵、FTRL完整golden/增量/随机与源边界；新增ACL读/写、FTRL第二键失败、损坏状态与数值、坏参数、安全日志和恢复。完成必要专项、完整offline回归以验收父EC06、目录/前端、24compile/hygiene后正常Git交付；EC07/08继续。
