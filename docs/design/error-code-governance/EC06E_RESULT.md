# EC06e 多模态回填验证

DONE/PASS（本地），基线7a4927168df4260311f9190c7f2343df8cc98ccc。晚unsupported不再声称未改任何数据；维度/非有限响应记失败、其他物品继续，最终明确部分失败。影响行数为1才算成功，删除行不重建。坏参数、缺目录/文本/全无海报明确码；原无海报/NULL文本跳过、零融合向量、成功计数limit和逐物品更新保持。

目录新增3码共233，旧230/8族保持。5源9交付文件，无SQL/schema/依赖/公式改变。

## 验证

- 基线7项真实pgvector全过；最终33后端/4类全过：Multimodal10、Args4、Runner5、Status14，0失败错误跳过。保护原jpg/jpeg/png优先级、融合精确值、成功limit、NULL/无海报、外层事务rollback和并发删除。
- 新增真实数据库CHECK拒绝第二次更新，第一项已生效、第二项旧值保持，解除约束后恢复。模型替身验证首个/晚unsupported、非法维度/NaN/Infinity/null与provider故障、安全码/原cause，失败无完成或未改数据谎报。未调用真实外部模型。
- 初轮唯一错误系测试恢复时Mockito when调用仍会执行旧异常stub；改doReturn后完整复验通过，产品断言未放宽。
- 57前端、生成物Prettier/ESLint、Python7、目录一致性、606Java90XML45SQL守卫、24compile/diff通过。Hygiene无blocking finding；HF-001将已提取的PROGRESS_INTERVAL=200误报inline magic number，现为具名日志周期常量且无业务行为变化，无需二次包装。Javaformatter/独立静态分析未配置，COMPLETE_WITH_LIMITATIONS。

5源指纹`325ab0f3445b5bf14dc6ee5cc0f33f13c0ab7178f207d7c6c7a02ecb092f9847`（排序路径NUL内容NUL）；/tmp/recsys-ec06e-{source,delivery}-paths.json。69549基线、33554最终测试、91157前端、99587编译/hygiene全部exit0；日志/tmp/recsys-ec06e-{baseline,test,test-final,frontend,compile}.log及hygiene.json。验证后源码未变。

D已正常ff/push7a492716。正常Git交付持续授权，8保护文件/暂停任务/唯一worktree保持，无数据清理/部署。回退会恢复错误提示，不能撤销已融合向量；失败后须核对范围，重复融合并非幂等，本片不新增自动重试。父EC06剩Bandit/FTRL；EC07/08及最终SHA CI五门继续。
