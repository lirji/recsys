# MP11b：三族事件投影

本片DONE/PASS。19源文件指纹 `fbc94a42c07d74a00da3d4adca642b715f25b79754839ccbf2c7bd849a56a2ac`。

Billing/Click/ConversionProjectionRepository分别迁入自己的Mapper/XML，共3/4/3条SQL。保留三族独立身份字段与固定表；没有引入通用空字段Fact或动态表名。配置、fixture、故障注入子类和浏览器宿主均使用实际SqlSessionTemplate，Service继续按真实工厂DataSource校验事务资源身份。

Inbox唯一键与金额汇总仍在同一事务，INSERT保留所有约束ON CONFLICT后的原摘要核对；独立eventId/资格/回执/业务事件冲突仍拒绝。CPC双回执按UUID顺序写共同身份索引，两行未全部写入就回滚；CPA可见与Outcome独立命名空间不混用。金额累加保留NUMERIC与原样CnyAmount解析，不因账户版本反序丢弃独立事实。Billing/Click沿用无单句超时，Conversion三句均保留5秒；服务事务截止时间不变。

## 验证

- 旧基线为MP11a三族事件60项及其余资金链MP10b374项；新增三族同URL不同DataSource拒绝保护后，旧ConversionEventsPostgresTest14项通过（`/tmp/recsys-mp11b-before.log`）。
- 三族PG/Kafka、配置/worker及对账联合72项/11类全部通过，0失败/错误/跳过（`/tmp/recsys-mp11b-after.log`）；保留已有并发重复、反序、Inbox/汇总前后失败回滚、跨kind身份冲突和broker/消费位点恢复测试。
- 推荐宿主3项和全仓24 reactor编译通过（`/tmp/recsys-mp11b-host.log`、`/tmp/recsys-mp11b-compile.log`），hygiene无阻塞（`/tmp/recsys-mp11b-hygiene.json`）；统一formatter/独立Java分析工具的既有限制保持。45历史SQL/8保护文件与19源文件指纹未变。MP11a与原生页签修复刚通过三套14项浏览器；本片不修改界面行为，最终全量门禁仍在MP15。

## 后续

MP11a=b8e01cc2、原生页签修复34a2a7a7已正常合并/push main，最新远程CI38735452724浏览器/自然/security均SUCCESS，build与money仍运行；console-ci38735452734全部SUCCESS。修复已通过远程浏览器，不能把MP10b的旧失败运行改记成功。完成本片后继续MP11c只读对账，再推进全部SQL和统一错误码目标。
