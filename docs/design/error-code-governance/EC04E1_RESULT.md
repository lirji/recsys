# EC04e1 严格兴趣读取

状态：DONE/PASS（本地验证）；基线e94cdac66beec1705a84b605f41ac75ee1d3bcce，详见EC04E1_DESIGN。父EC04仍IN_PROGRESS。

## 已实现与有意变化

UserProfileService不再吞DB查询异常；真实空/缺失/NULL仍为空。进程内gateway将失败映射USER_INTEREST_QUERY_FAILED HTTP503；gRPC gateway先按原状态和谓词完成Retry/CircuitBreaker，再由最终fallback抛同一公开错误。推荐专用GrpcUserProfileReader继续冷启动空降级，进程内推荐DbUserProfileReader未改。原写入、请求身份、SQL/事务及资金逻辑未改；无依赖/配置变更。

目录总136项，原135及8码族保持。User360局部兴趣失败也使用toApiError；ColdStartInterests原有错误入口直接生效。无新DTO/RPC/页面结构。方法内RPC异常保持安全StatusRuntimeException以保护重试；真实Spring代理出口才是受控PublicErrorException。滚动升级中旧engine仍可能吞owner的新失败，完整效果需要owner和engine都升级；不把此行为改进宣称完全无语义变化。

## 验证与限制

- 旧基线18项/4类（用户真实PG、召回真实PG、用户RPC、重试谓词）通过。
- 最终专项56项/7类，0失败/错误/跳过；含9条真实TCP+AOP+HTTP、实际owner→真实RPC、原PG坏JSON不再假空以及成功/重复/NULL/事务/映射保护；相邻普通RPC写20项全部通过。
- 真实代理验证：UNAVAILABLE/DEADLINE_EXCEEDED只尝试2次；其他3类错误只尝试1次；瞬时故障恢复返回真实兴趣；强制熔断0次调用；HTTP503不含categories/内部详情；推荐Reader仍空降级。进程内异常及恢复均验证。
- 首轮专项仅测试恢复时Mockito.when触发已有throw桩失败，改doReturn后通过；未修改产品来迎合测试或放宽断言。
- 前端154项/21文件、tsc+Vite build、生成TS/新测试Prettier、相关TS/TSX ESLint通过。3个真实React组件渲染测试覆盖带码错误、无伪空表单、真空可编辑及User360其余数据保留；接口mock，不宣称真实浏览器/真实后端联跑。jsdom不实现伪元素样式和既有Antd警告不影响测试。
- 全仓24模块compile、Python6项/生成check、600Java/90XML/45SQL守卫、diff/hygiene通过。Hygiene无阻断及advisory；Javaformatter/独立Java静态分析未配置。前端既有文件只改单行，不大范围格式化。
- 本片未重跑完整资金/自然/三浏览器；紧邻D的426资金是历史基线，本片含受影响普通写保护；最终EC08仍须完整门禁。

## 源码证据与命令

13源文件，/tmp/recsys-ec04e1-source-paths.json；排序路径NUL内容NUL SHA256：97a0ba81ce7ba6aca098eba574dc72c661de0906849ff8ca1c413f1a6c80b092。验证后未变。所有本地Maven串行，只用独占测试库。

```sh
mvn -B -pl recsys-rec-engine,recsys-user-service -am test '-Dtest=UserProfilePostgresTest,RecallInputsPostgresTest,UserGrpcErrorContractTest,GrpcRetryablePredicateTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-ad-serving,recsys-user-service -am test '-Dtest=UserProfilePostgresTest,RecallInputsPostgresTest,UserGrpcErrorContractTest,GrpcRetryablePredicateTest,UserInterestReadHttpTest,OrdinaryWriteHttpTest,OrdinaryRpcClientErrorTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console目录
npm run test:run
npm run build
npx --no-install prettier --check src/api/generated/errorCodes.ts src/pages/online/__tests__/InterestReadErrors.test.tsx
npx --no-install eslint src/api/generated/errorCodes.ts src/pages/project/User360.tsx src/pages/online/__tests__/InterestReadErrors.test.tsx
```

日志SHA256：

- `/tmp/recsys-ec04e1-before.log`：`dcd65629fd3ec72e672d9d49d6dee025a353748261432f6725cf82a285b3fbd2`
- `/tmp/recsys-ec04e1-after.log`：`f2969e973b43ab56c9732a40cc8c269191f7c6129805b47d8f252c475355c416`
- `/tmp/recsys-ec04e1-after-final.log`：`591c1170d76eaa81372ff61718f7a17b64b93c2349dfc81506c5adad6f4774d2`
- `/tmp/recsys-ec04e1-compile.log`：`220c06cccc43b7fd34a466bfde37c4f64c143168fca9b1a256439f4416304df6`
- `/tmp/recsys-ec04e1-frontend.log`：`c46a3d2ac0efea41231da1c010717a673f0a0de6815bfa3137fde422e2ed3b08`
- `/tmp/recsys-ec04e1-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`

## 后续和交付

17文件显式清单提交，持续授权正常ff/push main，保护用户文件和暂停交接，不清理未跟踪/忽略内容。D已交付e94cdac6，CI38801955042尚运行；C的CI38799881069终态CANCELLED，仅build被新push取消、其余四门成功。新片CI按新SHA核验。无schema回退；代码回退将恢复查询假空。父EC04e2继续console正常对象中的错误/未知结果，EC05～08保持未完成。
