# EC04e2 观测和诊断错误治理

状态：DONE/PASS（本地验证）；基线8d2fedee7155633c161596fa518b73ba333fe4e2；范围见EC04E2_DESIGN。父EC04及EC05～08仍未完成。

## 结果与边界

SystemMetrics拒绝非success、畸形响应、非vector/多条vector、非法或负的延迟/QPS值以及带warnings的可能部分结果；返回原HTTP200对象内available=false、全数值null和安全目录提示。有效空/NaN/Inf保持可用但无数值，不称“无流量”。单位/精度/请求/超时/禁用不外呼保持。自定义非聚合查询从取第一条/假空变显式不可用，属有意语义改进。协议依据见设计中官方来源。

SystemHealth主动探测采用ProbeStatus稳定code枚举，限制远端任意状态；失败/未知目录化，返回URL移除userinfo/query/fragment，实际探测地址不变。被动目标语义保持，HTTP错误仍DOWN。Diagnosis的四类catch使用受控WARN；空探测目标、质量指标缺失/非有限/非法范围不能默认PASS；指标不可用WARN、有效无值INFO。breach只展示安全计数及派生数值，不复制历史异常文本；不再在未核查PSI时声称PSI正常。共新增8个对象内错误码，目录总144，原136项/8族保持。

未改SQL/事务/依赖/基础设施/前端布局。SystemOps原始作业详情、User360主视图、Item、Report/Alerts剩余路径后续继续；DataQuality生产者跳过/失败和其他完整性仍归EC06。诊断当前PASS仅对应已取得的既有四项数据与信号，不将其作为全项目可靠性证明。

## 验证

- 旧console基线22项/4类通过；首轮62项/6类通过。审查发现新健康状态比较用了魔法字符串，已改显式编码枚举，并补warnings部分结果测试。
- 最终63项/6类，0失败/错误/跳过。真实本地HTTP覆盖状态/JSON/断连、有限数值单位与精度、合法空/NaN/Inf、禁用零请求、畸形/负值/多结果、第四查询失败、warnings部分结果、健康状态/凭据URL；MockMvc保留公开HTTP200结构。Diagnosis聚合源使用mock，验证四类失败、真实空/不完整/非法值与原正常阈值，不宣称真实Prometheus或生产故障演练。
- 前端154项/21文件、tsc+Vite build、生成TS Prettier/ESLint通过。最后Java审查修订未再改TS，前端证据仍对应相同前端内容。
- 最终全仓24模块compile、Python6项/生成check、600Java/90XML/45历史SQL守卫、diff通过。
- 最终Hygiene无阻断。1条枚举相似advisory指向被忽略的第三方PyTorch虚拟环境fuzzer.Status，与Java健康状态无业务关系，审查判不适用，不引入跨语言依赖。Javaformatter/独立静态分析未配置，保持IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；未伪称执行。
- 不重跑资金/自然/三sandbox浏览器：本片仅console观测/诊断、无这些产品路径改动；最终EC08仍须完整门禁，旧片结果不冒充本片SHA。

## 修订与命令

10源清单/tmp/recsys-ec04e2-source-paths.json，排序路径NUL内容NUL SHA256：049421b5c7235123d12b8696b80e7b4f5b56195c7786e4033d7aed28033992ce。最后修订重新验证，无后续源码变化。所有本地Maven串行，PG仅独占Testcontainers。

```sh
mvn -B -pl recsys-console -am test '-Dtest=SystemControllerTest,SystemOverviewServiceTest,SystemOpsServiceTest,ConsoleReadPostgresTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-console -am test '-Dtest=SystemControllerTest,SystemOverviewServiceTest,SystemOpsServiceTest,ConsoleReadPostgresTest,SystemObservationHttpTest,DiagnosisErrorTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console目录
npm run test:run
npm run build
npx --no-install prettier --check src/api/generated/errorCodes.ts
npx --no-install eslint src/api/generated/errorCodes.ts
```

日志SHA256：

- `/tmp/recsys-ec04e2-before.log`：`30aaad38e4a9a0d948b76db9e2f35ce3c5127734eb5f724c140f968584631b2a`
- `/tmp/recsys-ec04e2-after.log`：`abd9c18d8cffdac61484ca6f4b5cae8a21c66cb2fcc60d003674c273e94bb13b`
- `/tmp/recsys-ec04e2-after-final.log`：`bd90cb487de892bd0c4cf0f6281dd9739b44b94ec716223f40f5c24e0e5ab10d`
- `/tmp/recsys-ec04e2-compile.log`：`174d36616234440bc8543fa92db382f062692ef79a89c3bdad62e7b4d75e6efd`
- `/tmp/recsys-ec04e2-compile-final.log`：`fd2b5cdcd8ab07c9d9014b59bb2b224f5605bfcaed07a211c2238b91d8659e21`
- `/tmp/recsys-ec04e2-frontend.log`：`194f7e219c0ae6a9b1fae3ce65daa287801165c133af4e3f120e8d76be14fcc6`
- `/tmp/recsys-ec04e2-hygiene.json`：`901100c773b3d4add4ad502ecc1a144662589a29567b5beec0e0af09d38216b1`
- `/tmp/recsys-ec04e2-hygiene-final.json`：`e9764f4d6ade5b81c4ed64e91832582621b2d2967fe8b41d7fb0fa86dc84abb4`

## 交付与恢复

14文件显式清单，正常提交/ff/push main；保护8项用户文件/暂停交接，不清理未跟踪或忽略内容。实际SHA以Git和根交接为准。EC04e1 CI38802730985当前运行；D CI38801955042终态取消（安全/自然成功，钱/浏览器/build取消）。最终EC08须最终SHA五门。无数据库回退，代码回退会恢复旧未知当正常及内部详情风险。后续EC04剩余公开数据路径与EC05～08继续。
