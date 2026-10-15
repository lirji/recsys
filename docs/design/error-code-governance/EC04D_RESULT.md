# EC04d 普通写入未知结果

状态：DONE / PASS（本地验证）；基线1a61da1bd3362eb1efa86b22eefc2e9e59ec7a7d；范围见EC04D_DESIGN。父EC04仍IN_PROGRESS。

## 行为与边界

广告点击/转化/outcome和兴趣保存的RPC失败及熔断从HTTP假成功改为503。AD_WRITE_RESULT_UNKNOWN和USER_INTEREST_WRITE_RESULT_UNKNOWN使用统一安全提示，总135目录项，原133项和8码族保持。直接调用写方法也抛PublicErrorException，RPC cause已安全化；fallback保留已分类错误。点击/转化/兴趣必须收到成功ACK；outcome的正常false仍表示重复。成功空体/字段、请求身份、绑定400保持，无新增写重试，无资金算法/SQL/事务/基础设施变更。

真实Spring代理与TCP测试验证5种RPC失败、实际deadline丢应答、非成功ACK、强制熔断、成功和重复返回。服务端计数/输入记录只证明调用已到达及未自动重试，不作为数据库提交证明。丢应答不能声称未写入。原推荐读fallback保持，严格兴趣查询与其余公开对象错误留EC04e。

## 验证

- 原专项44项/4类通过；新增旧行为刻画8项通过，证明RPC失败原先仍HTTP200且调用一次。刻画首轮仅测试上下文遗漏grpc条件属性导致缺bean，补齐fixture后通过，未改产品或放宽断言。
- 最终专项69项/6类通过；完整资金426项/74类通过，全部0失败/错误/跳过。
- 前端151项/20文件、tsc+Vite build、两个TS文件Prettier/ESLint通过；全仓24模块compile通过。
- Python目录6项、生成check、600Java/90XML/45历史SQL守卫通过；git diff --check通过。
- Hygiene无阻断及advisory；IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：项目未配置统一Javaformatter/独立Java静态分析，未宣称执行。TS检查实际执行。
- 本片未改sandbox或页面流程，不重跑三套sandbox浏览器；EC04b的4/5/5及EC04c远端四门SUCCESS是历史证据，不冒充当前SHA。EC08仍须最终完整验证与最终CI五门。

## 修订与命令

9源文件显式清单/tmp/recsys-ec04d-source-paths.json；排序路径NUL内容NUL SHA256：ed1aa75267641f75bff791c3d124c32f7b5219ebcad19fa25ea7eb193e6b4b4f。验证后未变。所有本地Maven串行，集成测试使用独占Testcontainers。

```sh
mvn -B -pl recsys-ad-serving -am test '-Dtest=GrpcAdServingGatewayFallbackTest,OrdinaryRpcClientErrorTest,AdServingGrpcErrorContractTest,GlobalExceptionHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-ad-serving -am test -Dtest=OrdinaryWriteHttpTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-ad-serving -am test '-Dtest=OrdinaryWriteHttpTest,GrpcAdServingGatewayFallbackTest,OrdinaryRpcClientErrorTest,AdServingGrpcErrorContractTest,GlobalExceptionHandlerTest,SandboxRpcClientErrorTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console目录
npm run test:run
npm run build
npx --no-install prettier --check src/api/generated/errorCodes.ts src/api/__tests__/errors.test.ts
npx --no-install eslint src/api/generated/errorCodes.ts src/api/__tests__/errors.test.ts
```

日志SHA256：

- `/tmp/recsys-ec04d-before.log`：`12e58d423b676e58b67dcb15b94bcac31743a788a0d66bbc5e8f9ee78dcf49a9`
- `/tmp/recsys-ec04d-characterization.log`：`05fd0168030b0e07cb8f19b205078e461a17294906338ccb8aa77970d0deae86`
- `/tmp/recsys-ec04d-characterization-final.log`：`d05b12f5f35876b0111f4095a306cc34a17e054ff9a797d87dc084f9513105c5`
- `/tmp/recsys-ec04d-after.log`：`cef4056270c38f1a3040dbf8c755ff63848895ce9cedb1528267628cd968b3f7`
- `/tmp/recsys-ec04d-frontend.log`：`ae099e3b003529dd7b311476ef23adac8fc30c4b84efc5256ce419986e5df34b`
- `/tmp/recsys-ec04d-money.log`：`fb7b15f113705b582eca1d5237243cec6b81944d2d59af5b1fcd30e29839906d`
- `/tmp/recsys-ec04d-compile.log`：`2b7947dc3cd0cb4da3e2915f8382e09a6f0c832b1b42cf2ec0625f74730b5766`
- `/tmp/recsys-ec04d-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`

## 交付和恢复

13文件显式清单，复用任务分支，按持续授权正常提交、ff合并及push main；不stage八项保护文件，不清理未跟踪/忽略内容。实际SHA及CI以Git和根交接为准。回退本片无schema变更，但会恢复普通写假成功；后续目录引用需按逆依赖处理。EC04e继续严格读取与console错误，EC05～08仍未完成。
