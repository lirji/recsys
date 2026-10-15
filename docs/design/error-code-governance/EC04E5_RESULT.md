# EC04e5 物品元数据错误

状态DONE/PASS（本地），基线d36f184be44d1dbb818d24c1b7b058596a45ddcd。ItemController单查/批查故障返回CONSOLE_ITEM_QUERY_FAILED(503)，统一handler仅返回安全目录，不再假空。正常缺失、NULL、请求顺序/跳非法token/去重/200上限保持；useItemMeta继续静默#id回退，未改缓存和重试。底层Mapper已正确传播故障，无需修改SQL/内容服务。目录149项，原148/8族保持。

真实PG+MyBatis+MockMvc3项验证正常/缺失/上限/不可读503/恢复，既有ContentService5项保护持久化与缓存语义，公共HTTP28项，总36项0失败错误跳过；Redis未操作。新增React hook验证503时undefined回退、重新取得数据后恢复，前端164项/24文件、tsc+Vite build、相关ESLint通过。全仓24compile、Python6项、生成check、600Java/90XML/45历史SQL守卫、diff过。Hygiene无finding，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；Javaformatter和独立静态分析未配置，未伪称执行。测试是隔离HTTP组件集成，不是浏览器联跑；最终EC08仍需全门禁。

6源文件/tmp/recsys-ec04e5-source-paths.json；排序路径NUL内容NUL SHA256：c435997520b7ced03ea721c959379fa2144856669faddb9ff2a46e7f6557a176。命令：

```sh
mvn -B -pl recsys-console -am test '-Dtest=ItemReadPostgresTest,ContentServicePostgresTest,GlobalExceptionHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console
npm run test:run
npm run build
npx --no-install eslint src/api/generated/errorCodes.ts src/hooks/__tests__/useItemMeta.test.tsx
```

日志SHA256：

- `/tmp/recsys-ec04e5-test.log`：`3b6eeedbe3503e92df586ef5670ebb5798dc81576e066189aa4000c259864a20`
- `/tmp/recsys-ec04e5-frontend.log`：`599bf675b706fa9cbbcb7583a731fb003e12b63ba00a82ab77dba7d5a66539ac`
- `/tmp/recsys-ec04e5-compile.log`：`ae8b0b1f1d3c7ced2a9fa809208db152caa48cb92f56f46e5ab9af680cad6b70`
- `/tmp/recsys-ec04e5-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`

10文件显式交付，不含保护文件。无schema回退；代码回退恢复原假空。父EC04剩余SystemOps与EC05作业状态共同治理；EC05～08未完成，不以公共目录代替遗漏入口验收。
