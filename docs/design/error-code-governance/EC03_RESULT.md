# EC03 业务 HTTP 错误目录接入

状态：DONE / PASS；Hygiene 为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。基线 bb0b318bad9e7e655b61141add688a09c0ca295d，设计见 EC03_DESIGN.md；本片不代表整个错误治理目标完成。

## 实现与兼容

11 个业务控制器使用目录：10 个通过 ErrorFamily，预算非法 JSON 通过 ErrorCode.BAD_REQUEST。contracts/errors.json 增加 8 个显式码族的 prefix/fallback 引用；生成器同时生成纯 Java ErrorFamily，并验证引用、前缀、成员状态后缀。ApiError 增加码族工厂。三个广告交付控制器的 400/403/409/503 改为 HttpStatus。

既有 88 项错误、ErrorCode.java 与前端 errorCodes.ts 均字节不变。HTTP 状态、公开 code、ApiError 字段、原 ID 查询/重试说明、正常 202/409/422 业务结果、授权及状态分类 UNCHANGED。未登记的 429/599 等错误状态保持原码族和安全回退文案。预算非法 JSON 文案 CHANGED：从专用文本归一为 BAD_REQUEST 目录文案，400/code/字段不变；前端此前已使用目录文案。误用成功状态创建错误码族会被拒绝，实际业务没有此调用。

状态分类仍属于各业务控制器。CPM 与 CPC/CPA 对 InvalidProof 的原差异已由改前测试钉住，不借公共工厂合并领域分类。未新增 PublicErrorException 抛出点，后续迁移必须验证局部 catch 分派。没有改资金算法、事务传播、数据结构、SQL、基础设施或暂停任务。

## 测试保护与当前证据

所有 Maven 串行、真实数据库采用独占 Testcontainers。

| 检查 | 结果 |
|---|---|
| 改前 money-chain | PASS，384 项 / 68 类，0 失败/错误/跳过 |
| 改前 natural-exposure | PASS，75 项 / 13 类，0 失败/错误/跳过 |
| 新增兼容测试在旧实现上运行 | PASS，10 项 / 3 类，覆盖 10 个处理器的状态/码/文案/路径和脱敏 |
| 改后 HTTP/MVC/目录专项 | PASS，70 项 / 16 类，0 失败/错误/跳过 |
| Python 目录生成/无效元数据/防漂移 | PASS，5 项；生成 check 通过 |
| 持久层边界守卫 | PASS，598 production Java / 90 Mapper XML / 45 历史 SQL |
| 改后完整 money-chain | PASS，393 项 / 70 类，0 失败/错误/跳过 |
| 改后完整 natural-exposure | PASS，76 项 / 14 类，0 失败/错误/跳过 |
| 全仓 compile | PASS，24 模块 |
| Hygiene | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻断 |

新兼容测试复用本域处理器，测试不存在新增生产模块依赖；真实 MVC、PG 事务与消息恢复由原套件验证，不以纯 Mock 声称事务成立。此次前端源码/字典未变，未重复本地浏览器；交付后的 CI 继续运行三套真实浏览器，最终 EC08 还需全量验收。

## 源码与审查

21 源文件：contracts/errors.json，两个错误目录脚本，common 的 ErrorFamily 及测试，platform ApiError，engine 的 8 个控制器及 BusinessErrorContractTest，behavior 的 NaturalReceiptController 及测试，advertiser 的两个控制器、BusinessErrorContractTest 和预算 MVC 测试。

源指纹（按排序路径 NUL 内容 NUL 的 SHA-256）：`dcd17a57ca2451bc57a061ba5f57b0fb5683d79b0e6c5454a3af764d60324782`；明细 `/tmp/recsys-ec03-source-paths.json`。验证期间未改源码；交付前重新核对。

差异审查额外按原表达式核对全部 10 个状态分类，除数值改 HttpStatus 外完全一致。11 个局部工厂不再硬编码公开码/文案；没有新增任意 exception.message 公开路径。生成式契约避免 Java/TS 双份手工文案，common 保持不依赖 Spring。原 8 个受保护文件摘要及暂停交接后缀保持（根活跃前缀有意更新），不纳入此片提交。

## 后续

本片所有本地验收通过，允许独立提交并正常 ff/push main；远程 CI 不冒充本地结果。EC04～EC06、EC07 其余范围与 EC08 仍未完成。EC04 已只读发现 gRPC 的原异常描述透传和 console 正常响应内的内部错误详情，需下一片按正式设计处理。

## 质量限制与可追溯日志

Hygiene 的四条 advisory 都是 400/599 HTTP 协议边界，已经命名为 MIN/MAX_HTTP_ERROR_STATUS（生成器模板与生成类各两处），不属于散落业务数值。Java 统一 formatter 和独立静态分析未配置，未声称执行；沿用周边风格，git diff --check 通过。业务校验由专项及真实链路验证，工具未自动证明所有业务规则。没有新增依赖。

执行命令：

```sh
mvn -B -pl recsys-rec-engine,recsys-behavior,recsys-advertiser -am test -Dtest=BusinessErrorContractTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-rec-engine,recsys-behavior,recsys-advertiser -am test '-Dtest=ErrorFamilyTest,BusinessErrorContractTest,*ControllerTest,GlobalExceptionHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-rec-engine,recsys-behavior,recsys-gateway -am test -Dgroups=natural-exposure -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
python3 scripts/test_error_codes.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_persistence_boundaries.py
```

日志 SHA-256（临时日志丢失不等于可以伪造重跑结果，持久证据以本报告与对应提交为准）：

- `/tmp/recsys-ec03-before-money.log`：`6bfc1c78f52150c4e9992bac48e9d38bb07c417d54a12caae54f188ee5eacb13`
- `/tmp/recsys-ec03-before-natural.log`：`5d28a9d42e7bb866a5153d07f4467a5e754d210ebdf97ecf1b5e308c7eb92461`
- `/tmp/recsys-ec03-characterization.log`：`90d6533ee6be7da28c01fb7b26e0d08872dad524c38485195d230b24910c555a`
- `/tmp/recsys-ec03-http.log`：`b29bf7dd0d33ad025d7f476109ff441220f9a38c9bd80dbdfef9d59bdd984956`
- `/tmp/recsys-ec03-money.log`：`eb713cd331d7eb8949a806ed4d935df47138981912f64dde7654cc448f0f84de`
- `/tmp/recsys-ec03-natural.log`：`52f5ee440a5eee46b9fd1f7ad8f1ef56cff73b21ca0c0207f8e22657998d8476`
- `/tmp/recsys-ec03-compile.log`：`47542c43c0351ef8d152a619f1e68bf2bb1297b032c5ea543cf7fa38d19171ec`
- `/tmp/recsys-ec03-hygiene.json`：`74c48d23a8e001f6836c37b5b24645d7283be3311875e1d6d989ce5ead714315`
