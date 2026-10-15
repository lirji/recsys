# EC04c 普通RPC安全错误

状态：DONE / PASS（本地验证）；基线ba09172fda312c2d0e33c5aadccbab5a6ebc20ce；范围见EC04C_DESIGN，父EC04仍IN_PROGRESS。

## 实现与兼容

三个普通服务的8个方法使用GrpcErrors.respondWithUnknownOnFailure，调用和构造回复失败时返回安全目录码/描述/trailer；四类客户端的8个入口使用GrpcErrors.call，清除任意远端描述、cause及额外metadata，保留协议状态和StatusRuntimeException类型。普通读fallback的空hydrate/冷启动/无广告及旧写fallback返回保持；降级日志用目录码，写入应答失败只称结果未知，不再声称未落库或可安全重试。

实测关键边界：旧普通服务对内部IllegalArgumentException、IllegalStateException以及StatusRuntimeException(UNAVAILABLE)均返回UNKNOWN。因此服务端新适配固定保持UNKNOWN，不能直接复用sandbox的状态转译导致额外读重试。客户端保留网络实际返回的每一种gRPC状态及原重试谓词判定；没有增加写重试、改变熔断注解或配置。

目录新增6个RPC专用码（总133项），分别描述广告查询/报表/写入未知、内容查询、用户偏好读/写未知；原127项及8个码族逐项保持。protobuf schema、HTTP契约、领域逻辑/SQL/事务均未改。服务端只捕获业务调用及回复构造的RuntimeException；observer发送故障交回gRPC，不发送重复终态，Fatal Error不吞。

本片不把RPC目录化等同完整公开错误治理。旧写fallback在HTTP造成的伪成功、读失败和真实空结果的区分、console正常对象内错误均留在后续EC04d及之后，须显式修复并验证，不能把父EC04标DONE。

## 验证

- 改前真实TCP单服务刻画1项；随后三服务8方法及旧fallback/重试共13项/5类通过，0失败/错误/跳过。
- 改后83项/15类通过，0失败/错误/跳过。覆盖普通服务8方法真实TCP安全码/状态、广告成功ACK/重复outcome false/大ID报表、内容和用户成功恢复、四客户端8入口全部非OK gRPC状态/恶意详情隔离/重试判定、单一响应终态与Fatal Error；另保留sandbox真实TCP→HTTP、Servlet、Gateway真实HTTP和目录兼容测试。
- 前端149项/20文件、tsc+Vite build及生成TS的Prettier/ESLint通过。全仓24模块compile通过。Python6项、生成一致、600Java/90Mapper XML/45历史SQL守卫通过。
- 首轮after仅测试编译失败：proto模块没有Mockito。改为手写记录observer，未增加依赖；after-final是通过依据，断言不放宽。
- Hygiene无阻断、无advisory，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。Javaformatter/独立Java静态分析未配置，未伪称执行；TS检查单独实际执行。
- 资金406、自然76与三浏览器4/5/5为紧邻EC04b的基线证据，本片没有重新运行或宣称为当前SHA结果。本片不改sandbox/HTTP/前端解析或资金逻辑，新增测试覆盖实际RPC变化；EC08仍必须最终全量回归。

## 源码与范围审查

16源清单`/tmp/recsys-ec04c-source-paths.json`；排序路径NUL内容NUL SHA-256：`c2a3a95b87b3cda578d7427eecc546d166a39c0484b26cffc3a57d2f3008a945`，验证后未变。三个服务、四客户端、公共helper、目录与双端生成物、五个测试类；无pom/配置/SQL/脚本改动。源diff确认所有sandbox方法及Retry/CircuitBreaker注解保持。对响应终态的测试使用记录observer，真实TCP测试依赖mock，未将其作为数据库事务证明。

## 实际命令

```sh
mvn -B -pl recsys-content-service -am test -Dtest=ContentGrpcErrorContractTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-ad-serving,recsys-content-service,recsys-user-service -am test '-Dtest=AdServingGrpcErrorContractTest,ContentGrpcErrorContractTest,UserGrpcErrorContractTest,GrpcAdServingGatewayFallbackTest,GrpcRetryablePredicateTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-ad-serving,recsys-content-service,recsys-user-service,recsys-gateway -am test '-Dtest=*GrpcErrorContractTest,OrdinaryRpcClientErrorTest,GrpcResponseBoundaryTest,GrpcErrorsTest,GrpcRetryablePredicateTest,GrpcAdServingGatewayFallbackTest,SandboxRpcClientErrorTest,GlobalExceptionHandlerTest,GatewayErrorHttpTest,GatewayErrorResponsesTest,ErrorFamilyTest,ContentProtoMapperTest' -Dsurefire.failIfNoSpecifiedTests=false
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

所有本地Maven串行。日志SHA-256：

- `/tmp/recsys-ec04c-characterization.log`：`9b14eaa29911b9389bb0f5aab82fc832d9721d321f1a2d5855a7d015cffd117f`
- `/tmp/recsys-ec04c-before.log`：`c6fc367bd66986af1d7af4a4c8c9a2b7514758bee05868c41e6cffe7600c1b82`
- `/tmp/recsys-ec04c-after.log`：`2db60b8981539892c1aafbcbf7bc65f3e26a912ee2e7c4b7b853f75b279269c0`
- `/tmp/recsys-ec04c-after-final.log`：`e379694e0be3a7629ce114a6c4157395d81762500531c585216a93e99394ea27`
- `/tmp/recsys-ec04c-compile.log`：`c07baa0808ce63571fd16f11cdf3eb9da6071a62213e09ac835bc9c62a35aa0f`
- `/tmp/recsys-ec04c-frontend.log`：`7ca9279ef34d31c4cf4e2bfb2d18f20b34cedbd72a782db3d3aaa28608f23c66`
- `/tmp/recsys-ec04c-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`

## 交付与后续

按20文件显式清单正常提交、ff/push，保护8项用户文件与暂停交接，不清理未跟踪/忽略内容。最新CI必须按交付SHA核验，旧EC04b运行状态不作为本片CI结果。回退本片只恢复旧错误适配；无数据库回退，后续引用新增目录/helper的片需逆依赖回退。下一步EC04d处理旧HTTP写假成功与读降级含混，之后console边界和EC05～08剩余。
