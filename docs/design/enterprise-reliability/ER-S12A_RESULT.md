# ER-S12A：精确CPA候选与不可变报价

2026-10-15；基线6904889c6e38784a7b61d7fb01da3ab8edef60bd；原目录feat/sandbox-conversion-settlement。**S12A DONE / PASS，父S12仍IN_PROGRESS**。

Q-04已获用户确认。ConversionPrice固定CPA/PER_CONVERSION/CPA_GSP_CNY6_V1，复用已有GspPrice精确数学。实际BiddingService标准GSP路径只选CPA，使用pCTR×pCVR计价因子与手动出价/pacing上限；未知模式和VCG/Listwise显式拒绝。完整单次转化价格是后续预算义务，概率不折小预占。AdPipeline纯候选不写资金、旧曝光/反作弊去重或outcome。

V012新增独立ad_conversion_quote，完整上下文和价格依据摘要、数据库签发五分钟、操作位置唯一与不可变触发器。同ID原请求并发恢复原金额和期限；过期原报价仍可恢复但不能续期。旧CPM/CPC表不变；conversions-enabled默认关闭，sandbox及正确ad owner才装配，缺迁移/摘要漂移拒绝启动。本片没有转化接收/扣款端点或新中间件。

验证：专项14项（2金额、5真实竞价、6真实PG、1纯候选）全部通过；最终完整money-chain **262项/55类，0失败/错误/跳过，17模块SUCCESS**，包含旧CPM/CPC的PG/Kafka/HTTP/RPC。命令mvn -B -e -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false。日志/tmp/recsys-s12a-narrow.log、/tmp/recsys-s12a-money.log。新代码之外的旧浏览器装配修复6904889c已有广告4+自然5+混排5项本地通过。

PG证据包含12线程同请求、跨主体及位置/负载冲突、原期限和已过期历史、不可修改/删除、逐列中文注释、CPM/CPC同UUID独立表共存、default-off/owner/迁移门禁。独占fixture只在INSERT模拟历史时间，不修改产品不可变历史。上游概率/排序仍为原double表示，精确金额计算冻结其十进制表示，不宣称还原源精度；授权转化与资金闭环在S12B/C继续。

Hygiene=/tmp/recsys-er-s12a-hygiene.json：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻断。现有根级formatter/独立Java静态分析未配置；两个数字advisory指向已命名的TTL_SECONDS/MAX_SLOTS常量，已审查，无需引入新框架。diff --check通过。

验证源指纹b2aa077745b5fd3cd8b6129776c559eb9c346359366482224b9381aec4d773bf；11源文件清单/tmp/recsys-s12a-source-paths.json。同步C12_DESIGN、CONTRACTS、BACKEND_ARCHITECTURE、运行手册、IMPLEMENTATION_SLICES及CODEX_PROGRESS。七项用户文件指纹未变，console/test-results及忽略文件保留；只有原目录工作树。

SKILL_HANDOFF: backend-implementation → implementation-validation → project-documentation → update-progress-docs → task-git-delivery；slice=ER-S12A，validation=PASS，gate=PASS_WITH_LIMITATIONS。按已有授权正常提交/合并/push main，随后执行S12B；无真实收费切换或生产部署。
