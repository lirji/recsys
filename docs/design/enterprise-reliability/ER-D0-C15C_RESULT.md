# ER-D0/C15C：Seen 消费恢复设计门禁

本回合只设计，门禁 **DONE / PASS**。D-C01～D-C09通过；前置源码CI已核对8/8门禁成功。基线 `6c868e7349ba72682a8b7707fe7acdfcc98c3ed3`；正式契约在[C15_DESIGN](C15_DESIGN.md) C15C，唯一实施状态为[IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)。没有修改消费者、迁移、fixture、依赖或CI配置；没有运行或冒充C01～C18产品验收。

## 恢复证据

- 上轮B2分类整改实际文件与10个交付指纹一致；真实PG反例XML为3项/1失败/0错误跳过，失败是deleted组合把INVALID_SOURCE错判ALREADY_CAPTURED，结果/covered同时错误。绿色XML为PG15、Kafka2，全部0失败/错误/跳过；负例已由最小优先级修正关闭，不重跑有效验证。
- 11保护文件、5个已发布迁移/CPA、3个午夜fixture指纹均一致；所有产品文件另存本轮起始指纹，CODEX_PROGRESS原历史/部署正文单独摘要保护。
- 恢复时HEAD/main/origin-main同为上述完整SHA，前轮提交/ff交付与工作树相符。本轮原目录创建`feat/er-s15c-seen-recovery`，无新worktree、stash或清理；用户已有改动保留。
- [CI38947474433](https://github.com/lirji/recsys/actions/runs/38947474433)精确绑定上述完整SHA，最终completed/success，8个门禁全部success（build终止于2026-10-16T18:51:20Z）。逐job原始证据保存到`.dev/codex-flow-reliability/round-89a217e3/preceding-ci-final.json`；未取消或重跑，无未处理验证失败。另以git ls-remote只读确认远程main同SHA。
- 父笔记`C15C_PARENT_NOTES.md`仅作为只读输入：旧catch、latest、默认错误处理器、所有权及Redis历史集合丢失均有源码/机制证据，没有有效既有C15C门禁可复用。

## 设计验证矩阵

同一Agent在起草后进行分离只读验证，不声称第二评审者或运行了子Agent。

| ID | 检查与证据位置 | 结论 |
|---|---|---|
| D-C01 | C-F：旧入口/新codec/实际依赖/primary持久化/父笔记；Boot BOM、实际classpath、本机3.1.4 JAR API与官方版本文档一致 | PASS |
| D-C02 | C-D/C-C：比较默认处理器、自写轮询和专用container；正反馈/合法无效果/非法/依赖/未知错误分列；旧格式无hash身份；三流范围完整 | PASS |
| D-C03 | C-S：旧组ADOPT不改位点，新组冻结最早保留起点；新增分区单独登记；初始化外部写结果未知先查询；已有offset丢失/过期不reset | PASS |
| D-C04 | C-P/C-O：engine自有表/checksum、技术坐标/原字节、主库短事务、3次持久预算/lease/fence、Redis在事务外；SADD后结果/ACK窗口不伪称原子 | PASS |
| D-C05 | C-S/C-R：稳定命令、actor/reason、并发租约/activeCommandId、原字节重放、超限坐标及保留缺失、Redis ACK后丢失边界与局部RESTORE | PASS |
| D-C06 | C-O：显式factory属性、无默认recoverer、nack/暂停/停止和跨进程恢复；本机字节码揭示重平衡/Wakeup内部提交窗口，已单独写明并要求真实offset验收 | PASS |
| D-C07 | C-I：C1旧Seen完整恢复闭环→C2新版接入与父片收口，具体Java/Mapper/XML/SQL/配置/测试路径，默认关闭且无Flink历史累加 | PASS |
| D-C08 | C-V：C01～C18包含真实Kafka/Redis/PG、独立JVM、重平衡、毒消息/稳定命令、new-group预发布/既有offset、Redis丢集合负对照，均标UNVERIFIED | PASS |
| D-C09 | 正式文档交叉引用/状态、源码不变、11保护/旧迁移/fixture/历史正文、仅本地设计提交及无副作用边界 | PASS |

审查期间关闭的设计问题：

1. 新增分区与全新组初始化条件不同：补ENROLL_NEW_PARTITIONS，只允许未登记且无committed分区，旧分区不覆盖，缺旧offset不能假装新分区。
2. 手工恢复与普通listener可能争抢同记录：补activeCommandId，恢复同时约束command/record lease与fence，普通listener等待或暂停。
3. 不能假定所有commit失败会从acknowledge抛出：核对本机3.1.4 ListenerConsumer字节码，RebalanceInProgress暂存提交、Wakeup被处理；明确ACK请求不等于broker确认，C04/C05/C09验证这两个窗口和真实committed。
4. 每流唯一绑定不支持任意另组扫描：删除本片提供另组全量重建的歧义，RESTORE仅已有record，其他重建须另行owner方案；补第3次命令领取退出的耗尽清算，避免activeCommandId永久占用。
5. 初始化同ID重试不能重新采样beginningOffset：先读持久原命令，保持冻结清单，部分外部成功先核对，仅补缺项，内容/actor/reason变更冲突。

## 产品未验证、风险与交付安排

C01～C18全部未执行；旧Seen吞错当前源码仍在，须由C1实际修改。设计不等于新listener可用、shared切换或完整Seen恢复。新增每记录PG持久化与原文保存有容量成本；生产吞吐、保留、RTO/RPO未测。Redis ACK后丢集合不自动全量恢复，旧吞错已提交缺口、历史来源/保留之外不伪造；history不进入旧Flink，S19仍独立。

本片在`feat/er-s15c-seen-recovery`只做独立本地设计提交；不合并、不push、不触发新的main CI。下一实施回合复用此分支执行ER-S15C1，保留设计和实现两个逻辑提交，实施必要验证完成后再正常ff合并/push。S15C2之后才可依据实际验收关闭父S15C/S15；随后S16～21，S22～25暂停。本回合结束返回父协调者，不递归运行codex-flow。

最终文档链接、git diff --check、1335个产品/测试/构建/CI文件指纹、11保护及CODEX_PROGRESS历史正文核对PASS；本轮无产品测试重跑，也不宣称formatter/独立Java静态分析已完成。

详细核对和交付证据保留在`.dev/codex-flow-reliability/round-89a217e3/`，不提交运行材料。无共享数据/开关/部署、无未知结果操作重跑；最终Git SHA和分支以该目录delivery.json及实际Git为准。
