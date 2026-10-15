# MP06b：自然曝光回执事务迁移

本片DONE/PASS；8个源文件指纹 `ca3485e804c6cb251a216985b20d1d746e55c5d2b170244d252cd84998ce19a9`。验证后仅同步文档。

NaturalReceiptRepository的schema探针、数据库时钟、事务超时、资格锁、回执、事实与Outbox SQL迁入primary NaturalReceiptMapper/XML。Repository保留主体/摘要校验、状态转换和影响行数检查；不持久化原资格票据。新增共享UUID类型处理，保留原生UUID绑定及NULL。Mapper与事务管理器复用同一个primary DataSource，STATEMENT缓存范围确保锁后重新取数据库时间；历史V001脚本保持字节不变。

## 验证

- 旧实现15项真实PG回执测试通过，基线见MP06a-after.log；迁移后新增同连接超时/时钟及启用配置装配2项。
- 最终30项/5类全部通过，0失败/错误/跳过：自然回执17、配置2、Controller4、行为单测4、行为PG3。日志 `/tmp/recsys-mp06b-after-final.log`。
- 覆盖同资格并发唯一事实、重复/跨主体、过期（含锁等待后过期）、四个写阶段故障回滚、事实与Outbox原子性、NULL UUID与同连接SET LOCAL、禁缓存数据库时间。
- 新装配测试初次使用未批准政策test-only，被原校验正确拒绝；仅修正测试使用已批准TEXT_POLICY_V1，最终通过，未更改生产政策。
- 自然浏览器5项、混排浏览器5项全部通过，分别执行scripts/test-natural-exposure.py、scripts/test-feed-exposure.py。真实Chromium+独占PG验证恢复、身份隔离、hidden/离屏、最终位置及资金/自然事实；不宣称这两个脚本验证了Kafka投递。日志 `/tmp/recsys-mp06b-{natural,feed}-browser.log`。
- 全仓24 reactor编译成功，`/tmp/recsys-mp06b-compile.log`。Hygiene见 `/tmp/recsys-mp06b-hygiene.json`，统一formatter/独立Java静态分析尚缺。
- 45个历史SQL和8个保护文件摘要不变，未操作共享数据库。

## Git与后续

MP06a已正常提交/ff合并/push main：63ae139b0b54076c7b75853c8ccfd9df7d34eccf；CI38722565155最后观察IN_PROGRESS。继续MP06c控制台画像/报表，保留可选数据库、逐查询降级及CSV回退；之后MP07～MP15和统一错误码目标。
