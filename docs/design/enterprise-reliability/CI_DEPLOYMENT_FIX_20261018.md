# 2026-10-18 本机部署前CI修复

用户要求将最新代码更新到现有Docker。预检精确34025d0e的CI39094641915，发现streaming-reliability和recall-resilience失败；旧应用继续运行，先修复直接阻断。初次修复范围为测试依赖/fixture；下述追加修复补齐Mapper写入的禁用查询缓存声明。均不改变产品预算、SQL语句、数据库结构、事件、API或功能开关。

- Streaming：最终依赖图选择Flink的Commons Compress 1.26.0；codec为optional，Testcontainers向Kafka容器复制启动文件的tar路径抛`NoClassDefFoundError: org/apache/commons/codec/Charsets`。早期本地恢复报告用的是1.24.0，不足以证明最终依赖图；补test-scope commons-codec，沿现有Boot BOM 1.16.1，不把测试宿主引入Flink产品运行包。
- HOT：健康第一次读取也用了30ms，CI首次加载/worker启动时可能按合法超时契约返回空。fixture先用1000ms完成真实Redis预热，再切回原30ms故障预算；仍保留32并发、350ms外部等待余量、DB单飞/峰值=1及恢复断言，未扩大产品预算或跳过验证。

最终同版必要验证33项零失败/错误/跳过：streaming-reliability 13项真实Kafka/Redis/PG、checkpoint恢复/保留缺口/Redis暂停；recall-resilience 19项含有界资源/上下文/真实Redis/HTTP影子混合负载；显式CategoryEnricherArtifactIT 1项验证最终shade产物的独立JVM真实PG与SPI。先前verify使用groups会筛掉无标签IT，已用`-Dit.test=CategoryEnricherArtifactIT`明确补验，未混用陈旧XML。

证据为本机`.dev/deployments/latest-34025d0eca35/{ci-reports,CI_FIX_TEST_RESULT.json,streaming-fix-validation.log,recall-fix-validation.log,artifact-fix-validation.log,ci-fix-hygiene.log}`。Hygiene无阻断但统一Java formatter/静态分析器未配置；单独只读复核确认test-scope和故障断言保持。修复正常提交后以最终精确ref重新核对远程CI；本地PASS不代替远程门禁。部署实际结果/回退/镜像证据见同目录PROGRESS.md，不在本报告提前宣称部署完成。

## 追加：Mapper RETURNING 禁用查询缓存

2453d98e精确CI39095753177的10个专项均PASS；全量build中的Maven clean verify、ONNX和跨语言契约步骤均PASS，但随后持久化边界静态检查发现CatalogProjectionMapper.addTask/claimTask缺useCache=false。两个写入已声明affectData=true与flushCache=true，此次补齐useCache=false，确保返回行的写入不参与查询缓存；SQL、事务和任务重试语义保持。未放宽静态门禁。

修复后真实PG/Redis投影回归及默认关闭配置共15项零失败/错误/跳过，覆盖新增任务、跨广告任务次序、过期租约/旧fence拒绝、Redis恢复与原子性；持久化扫描/测试、公开错误扫描/生成一致性、ONNX及跨语言契约共7条快速检查命令全部通过。证据MAPPER_CACHE_TEST_RESULT.json、mapper-cache-validation.log及mapper-fast-gates.json位于同一部署目录。新修复提交后重新绑定源码和镜像，精确CI终态和部署状态以该目录PROGRESS.md为准。
