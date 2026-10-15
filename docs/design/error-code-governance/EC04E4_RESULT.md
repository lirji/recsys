# EC04e4 用户360部分读取未知

状态：DONE/PASS（本地）；基线3434795d17ba031bab0da5e50fb038d863bad949。父EC04及EC05～08继续。

保留成功分区；失败标量为null，errors使用显式Section code和本地错误目录。存在性只在画像/完整行为可证明时true，两源都成功且没有记录才false；否则未知。流式计数中断丢弃半份结果。Redis坏值/超时/缺依赖使整个实时分区未知，真实缺键仍为空/0/false，合法浮点计数仍向零取整。前端保留成功信息、失败显示未知与安全码，不复制任意响应errors文案；旧成功响应无需errors仍兼容。失败null是有意语义变化，需配套新版前端；不宣称旧前端也支持完整未知展示。

## 验证

- 后端113项/10类（console85+公共HTTP28），0失败错误跳过；真实PG表故障验证存在性三态、恢复、HTTP分区键和null保留；受控Mapper验证游标中断不显示部分总数；独占Redis9项覆盖缺键、浮点数、损坏/越界计数、坏序列、真实暂停超时与恢复。无共享数据库/Redis写入。
- 前端163项/23文件、tsc+Vite build、生成物/新测试Prettier与相关TS/TSX ESLint通过。新增6项真实React组件测试（API替身）覆盖部分成功、全未知、安全码、确定缺失、旧成功、旧实时态不可用、兴趣在途。首轮测试把独立成功的空兴趣“无”也禁止，已将断言限定到失败缓存字段；产品行为未放宽。
- 全仓24模块compile；Python6项、生成check、600Java/90XML/45历史SQL守卫、diff检查通过。目录148项，原146及8族保持。
- Hygiene无finding，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；统一Javaformatter和独立Java静态分析未配置，未伪称执行。无SQL/schema/事务/新依赖/新重试变化；各分区不承诺同一事务快照。最终全仓测试、资金/自然/浏览器及最终SHA CI仍归EC08。

## 修订与命令

13源清单/tmp/recsys-ec04e4-source-paths.json；排序路径NUL内容NUL SHA256：ac2883a37a27ed298cd580c97bebd058c8e2320e8f98de6246c0178f149ba498。

```sh
mvn -B -pl recsys-console -am test '-Dtest=SystemControllerTest,SystemOverviewServiceTest,SystemOpsServiceTest,ConsoleReadPostgresTest,SystemObservationHttpTest,DiagnosisErrorTest,AlertsErrorTest,GlobalExceptionHandlerTest,UserProfileFailureTest,UserRealtimeRedisTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
# console目录
npm run test:run
npm run build
npx --no-install prettier --check src/api/generated/errorCodes.ts src/pages/project/__tests__/User360Errors.test.tsx
npx --no-install eslint src/api/generated/errorCodes.ts src/api/types.ts src/api/user360.ts src/pages/project/User360.tsx src/pages/project/__tests__/User360Errors.test.tsx
```

日志SHA256：

- `/tmp/recsys-ec04e4-after.log`：`0d9389ce48d2f106e753bb8acb3d10040afe040975478033536ecaa9b82e0faa`
- `/tmp/recsys-ec04e4-frontend.log`：`bb57a9ad1177bcbab5a5bdd676922dd7dbee280809bc3e64f8af6c550d98dcf8`
- `/tmp/recsys-ec04e4-frontend-final.log`：`cbd9b9ee307fe446c443ee33dec5453677ed8b798d480ae2e2c0a0df1e3490fb`
- `/tmp/recsys-ec04e4-compile.log`：`b8dac685c02d3c2b2d5d6500c73ac0fa2d99f10fa07ab43001e9d6d2c687657e`
- `/tmp/recsys-ec04e4-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`

17文件正常任务commit/ff/push授权，排除8个用户文件和测试输出。回退无schema变更，但恢复旧假空语义。E3 CI38805179085仍运行；E2 CI38803835991最终四门成功、build取消，非五门成功。接续Item、SystemOps/作业契约与EC05～08。
