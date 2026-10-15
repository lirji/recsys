# EC04b sandbox客户端与预算恢复提示

状态：DONE / PASS（本地验证）；基线bf078299023ec5d4f2bd4036b9648cf4eee26667，范围见EC04B_DESIGN。父EC04仍IN_PROGRESS。

## 行为与兼容

三个sandbox客户端接入共享目录。预算通过PublicHttpException保留ResponseStatusException类型契约；ApiError使用目录wireCode，原BAD_REQUEST/CONFLICT/NOT_FOUND/BAD_GATEWAY/SERVICE_UNAVAILABLE仍不变。目录新增12项，总127项；原115项与8个码族逐项一致。wireCode只允许引用同一HTTP状态的非别名条目，禁止循环、链式引用、默认项或HTTP0。

前端仅在实际HTTP状态、旧code、完整消息均匹配本地目录候选时恢复上下文提示；展示仍来自本地目录。附加文本、HTML、对象、数组、未知/不匹配code都不能提供展示消息。全局HTTP处理器从受控异常的目录项构造响应，忽略可变ProblemDetail。预算结果未知/暂未观察到时保留原commandId查询说明。

有意变化：内部RPC UNAUTHENTICATED以及带单一明确caller码的PERMISSION_DENIED返回HTTP503；真正的领域证明拒绝保持403。四个服务caller拒绝新增分类码，gRPC状态不变；旧服务无metadata、未知/重复码及不匹配状态保持旧分类。CPM、CPC与曝光回执的损坏owner回复变为依赖未知，不再误报用户400。既有CPA身份校验保留。

未改变：成功/持久化拒绝对象、HTTP字段/既有码、查询404空值、原命令恢复、资金算法/事务/SQL、RPC重试及deadline策略。无新依赖。安全描述替代原样RPC异常；不回传内部原因或任意trailer。

## 验证

- 改前47项/9类，0失败/错误/跳过；改后专项72项/13类，0失败/错误/跳过。
- 五条真实TCP→客户端→Controller链路验证匿名内部服务、签名有效但caller错误、受信caller后的领域拒绝/未知结果；HTTP主体始终已授权，身份变化仅发生于内部RPC。资金owner与授权SDK在该专项使用mock，事务正确性由完整独占PG套件验证。
- 损坏回复专项覆盖12条CPM/CPC/曝光读写路径，验证合法输入不变400、不变空结果，写调用次数为1；现有预算真实TCP超时后可按原commandId查询已生效结果。
- 前端149项/20文件、tsc+Vite build、受影响3个TS的Prettier/ESLint通过。Python6项、生成一致检查、600生产Java/90Mapper XML/45历史SQL守卫通过。
- 首轮TCP到HTTP新测试未声明Accept，选择了非JSON转换器，导致5个JSON路径断言失败；补齐JSON Accept与Content-Type断言后全过，未放宽状态/码/消息检查。
- 完整资金406项/73类、自然76项/14类，0失败/错误/跳过；全仓24模块编译通过。三套真实Chromium全部通过：广告4项、自然5项、混排5项；各独占后端宿主1项全部通过。广告还验证真实Kafka投影与最终资金对账；自然/混排验证PG事实和原子Outbox，未将它们声明为Kafka投递验证。
- Hygiene无阻断、无advisory，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。Javaformatter及独立Java静态分析未配置；TS检查已单独实际执行。

## 源码与交付边界

24源文件清单`/tmp/recsys-ec04b-source-paths.json`；按排序路径NUL内容NUL的SHA-256：`740d83f1dd96302d6c1b994734518aaab82de822eb71d8236cd13b7cfa052343`。验证后已复核源指纹不变。CODEX_PROGRESS及7个原用户文件不纳入提交，暂停任务后缀保持；不清理用户未跟踪或忽略文件。

父EC04的普通AdServing/Content/User RPC、其余HTTP与正常对象内错误仍待后续；EC05/06/07剩余及EC08继续。新提交远程CI须单独核验，不能将旧SHA或被取消的build记为通过。回退本片恢复原错误适配，不涉及数据迁移；若已有后续片引用新增目录/helper，应先逆依赖顺序回退。

基线bf078299的CI38795249788已完成，五门全部SUCCESS。此结论仅属于EC04a基线；EC04b提交后仍须独立核验新SHA。

## 实际命令与证据

全部本地Maven及含Maven脚本串行，数据库与Kafka来自独占Testcontainers；不使用共享库。首轮失败日志单独保留，最终专项日志after-final为通过依据。

```sh
mvn -B -pl recsys-ad-serving -am test '-Dtest=GlobalExceptionHandlerTest,GrpcErrorsTest,GrpcSandboxBudgetClientTest,SandboxGatewayTest,SandboxImpressionGatewayTest,ClickOnlineBoundaryTest,ConversionOnlineBoundaryTest,BusinessErrorContractTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-ad-serving -am test '-Dtest=GlobalExceptionHandlerTest,GrpcErrorsTest,InternalAuthGrpcServerInterceptorTest,GrpcSandboxBudgetClientTest,SandboxGatewayTest,SandboxImpressionGatewayTest,SandboxMalformedReplyGatewayTest,ClickOnlineBoundaryTest,ConversionOnlineBoundaryTest,BusinessErrorContractTest,SandboxGrpcErrorContractTest,SandboxRpcClientErrorTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-rec-engine,recsys-behavior,recsys-gateway -am test -Dgroups=natural-exposure -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
python3 scripts/test-sandbox-exposure.py
python3 scripts/test-natural-exposure.py
python3 scripts/test-feed-exposure.py
# console目录
npm run test:run
npm run build
npx --no-install prettier --check src/api/errors.ts src/api/__tests__/errors.test.ts src/api/generated/errorCodes.ts
npx --no-install eslint src/api/errors.ts src/api/__tests__/errors.test.ts src/api/generated/errorCodes.ts
```

日志SHA-256（frontend-tool-output不含首段启动横幅；原会话44134终态exit=0）：

- `/tmp/recsys-ec04b-before.log`：`12abb8c4c3dbd7649a67a37d3bc1cd5b605149c36fd5721ed39335bbf9e5509d`
- `/tmp/recsys-ec04b-after.log`：`b639e537e6d9eb8c7621d201a9470fef3ccb0330a80f40ef4e0e2192008cd9f1`
- `/tmp/recsys-ec04b-after-final.log`：`75b420449d2f70b40f0497a62bb0270199cf76ebb14bb395e65c07a2b1593fc2`
- `/tmp/recsys-ec04b-money.log`：`4fb612eaf008a0fd3a282a0022b998f37088cdee2b0e36ef63451e5ba991df71`
- `/tmp/recsys-ec04b-natural.log`：`6b610aec1fa2eaa553c71fa702cddc1597e95a492abb82ca8d29cfc49f9fd0ed`
- `/tmp/recsys-ec04b-compile.log`：`237e26cb8be3042b83270081eaaae7b5dba9ebe5b9ad6eab2a7bcfdfaa6f4583`
- `/tmp/recsys-ec04b-browser-ad.log`：`05256e4aaaed6a65953e39ed1b19122ac0f746ae3a4c4b725d7fa2aadf000e10`
- `/tmp/recsys-ec04b-browser-natural.log`：`7f2db1151e5bcea3606d1f1abcb22d77b90bf3cebe7b6a2fbb8c72c5183f27fa`
- `/tmp/recsys-ec04b-browser-feed.log`：`d8fe589fd8e4f06a4c904a700e795060b57d99361fd35d66bb5036487a704d69`
- `/tmp/recsys-ec04b-sandbox-backend.log`：`1fb4023d43e0e65b53848a2c1877eb0d4c3b23cd5b736eb46dbf60438ba05e98`
- `/tmp/recsys-ec04b-natural-backend.log`：`4ccb5ee37e72839ccadddb92d08a961500118984ac3315305b1a7ae1c729dd0b`
- `/tmp/recsys-ec04b-feed-backend.log`：`7c446b57ef159fe86c444e3844efd5cbad4e0b8d47321de313d350d45df29e4a`
- `/tmp/recsys-ec04b-frontend-tool-output.log`：`d9c18e84331ca76259831ed73e9d59ded8ddaf3df0dcc31ab9dc2ddf90020748`
- `/tmp/recsys-ec04b-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`
