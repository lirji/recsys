# EC06d oCPC 校准验证

DONE/PASS（本地），基线6f5c1b8f227d42c040cdc418c2765aaf32fbb6c9。缺失旧k仍从1开始，缺λ仍按原始计数且显示目录降级码；损坏或不可读模型不再伪装缺失并覆盖旧值。严格参数、非有限/非法统计与计算、舍入零/饱和拒绝；无目标明确无输入。无花费/转化保持系数仍合法完成。正常算法/SQL/源/窗口/逐广告主发布均保持；不宣称多键回滚。

目录新增4码，共230，旧226/8族不变。5源、9交付文件。无依赖、SQL、schema、在线出价改变。

## 验证

- 改前真实双PG/分片/Redis9项全过；改后45项/5类全过：Ocpc13、DelayModel9、JobArguments4、JobRunnerError5、JobStatusRecord14，0失败/错误/跳过。
- 原均值/重复归因/窗口边界/非正days epoch下界、延迟权重、完整long与clamp结果保持。无转化保持和裸delay-correct开关保持。
- 真实Redis ACL分别拒绝get及第二广告主set，WRONGTYPE、坏模型、非法参数、NaN/Infinity/负花费、计算溢出、舍入零/饱和验证；失败无完成日志，先前已发布保留，修复恢复成功。
- MyBatis包装ResultHandler异常，显式保留其受控码；ShardingSphere AVG使用BigDecimal合并非有限目标时在回调前拒绝，测试精确确认根因NumberFormatException、JOB_OCPC_FAILED与旧值保持。初轮两次失败的源码/日志原因均已修复或按真实驱动分类明确，不放宽到任意失败。
- 57前端目录用例、生成物格式/ESLint、Python7/目录一致性、606Java90XML45SQL守卫、24模块compile及diff通过。最终Hygiene无finding；Javaformatter/独立静态分析未配置，COMPLETE_WITH_LIMITATIONS。

源码指纹 `01f2400c15d0840c067021978d9ab3ccdbcc95a3adfafbd3ea2a24c73e597020`（排序路径NUL内容NUL），清单/tmp/recsys-ec06d-source-paths.json；验证后无源码改变。日志/tmp/recsys-ec06d-{baseline,test,test-final,test-verified,frontend,compile}.log和hygiene-final.json。14189基线、15999最终专项、81684前端、86949编译/hygiene全部exit0。中间hygiene曾过早传入UNIT_TEST=PASS，已用hygiene-pending.json纠正FAIL，最终结论只依据test-verified成功后的hygiene-final.json。

EC06c2已正常ff/push6f5c1b8f；其CI38820005105仍运行，C1 CI取消，不称全绿。持续授权正常Git交付；8保护文件及暂停任务保持，无清理/部署。代码回退恢复静默回退，不能撤销已写Redis系数。父EC06剩Multimodal/Bandit/FTRL、EC07/08继续。
