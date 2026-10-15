# 广告模拟连接释放探针修正

前序19592ca6的[CI38749329611](https://github.com/lirji/recsys/actions/runs/38749329611)中，build唯一失败是SimAdEventsPostgresTest.defaultTwentyThousandRequestsPreserveRelationsNullColumnsAndDelayedConversions结束后pg_stat_activity瞬时仍为1。业务断言及14847部分提交断言通过，其余四门禁SUCCESS。原失败日志`/tmp/recsys-ci-38749329611-failed.log`。

冻结版本[pgJDBC关闭实现](https://raw.githubusercontent.com/pgjdbc/pgjdbc/REL42.7.3/pgjdbc/src/main/java/org/postgresql/core/QueryExecutorCloseAction.java)发送Terminate、flush并关闭socket，不等待服务端退出确认。因此仅查询统计视图的瞬时状态不能区分客户端泄漏与服务端退出延迟；原CI没有记录连接对象，不能反推其具体瞬时状态。

新增测试专用TrackedDriverDataSource保存真实Connection引用，不做代理、不修改产品连接生命周期。探针立即断言所有Connection.isClosed，再以20ms间隔、最多5秒观察服务端会话归零；不替作业close、不重试业务、不忽略仍未消失的会话。增加反向测试：故意持有打开的真实连接时必须立即断言失败，关闭后才能通过。复用既有starter-test的Awaitility，无新增依赖。

本地专项8项全部通过（`/tmp/recsys-sim-ad-close-probe.log`），涵盖实际客户端关闭、事务提交/回滚、部分失败和探针反向验证。全仓测试编译另见`/tmp/recsys-sim-ad-close-compile.log`；纯测试差异hygiene判N/A并人工检查。2源指纹`a8170762ae2df93f864f28886e2666481999642883f689a1f7ab5a585447eebe`。

本次不宣称旧CI变绿；提交后由新CI完整验证。产品Mapper和批处理helper未改。可空ID修复52f9fba8与此修正按独立逻辑提交后一起正常ff/push main。
