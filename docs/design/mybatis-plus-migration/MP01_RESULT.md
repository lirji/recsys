# MP00 / MP01 验证结果

2026-10-15；父任务全量迁移仍 IN_PROGRESS。本报告只证明基础接入与用户偏好路径。

验证后12个生产/测试/POM文件的路径与内容SHA-256：`06ce17f4352f6d3c21ba04d501cef8e49e6355ce3b6bda0d3c44124e6365b451`；此后只同步文档。

## 已实现

- 根 POM 集中管理 MP 3.5.17 / MyBatis-Spring 3.0.5；保持 Boot 3.2.5 / Spring 6.1.6 / Java21 / PG 驱动42.7.3。
- 新增 recsys-persistence：JSONB 类型处理、无二级缓存/STATEMENT 本地缓存约定、显式 DataSource 的 Spring factory 组装；Spring 相关依赖 optional，不传递到非 Spring 宿主，不创建连接池。
- 默认源 factory 仅加载 `mapper/*/primary/**/*.xml`；UserPersistenceConfiguration 只扫描用户 Mapper，业务服务不再持有 JdbcTemplate 或 SQL。
- UserProfileMapper + XML 保持 JSONB 合并、数组顺序/重复项、非法形状读降级、null 类目清空与事务行为。

共享模块除 TypeHandler 外增加极小 factory 配置复用，是已有多个 Boot 宿主需要保持同一缓存/事务策略的实现细化；不包含任意 SQL 执行器或业务仓储，不改变已批准业务边界。

## 实际验证

| 检查 | 结果 | 证据 |
|---|---|---|
| 原全仓测试基线 | PASS：694 测试，0失败/错误，4跳过，149测试类 | `/tmp/recsys-mybatis-baseline-test.log`，4分10秒 |
| 旧 JDBC 偏好特征保护 | PASS：6项真实 PG，0失败/错误/跳过 | `/tmp/recsys-mp01-user-before.log` |
| MP 偏好与 Boot 装配 | PASS：8项真实 PG，0失败/错误/跳过 | `/tmp/recsys-mp01-hosts.log` |
| 推荐宿主完整读取链路 | PASS：RecommendIntegrationTest 3项，真实 PG/Redis | 同上 |
| 全仓增量编译 | PASS，24 reactor 项目 | `/tmp/recsys-mp01-compile.log` |
| 用户模块及依赖打包/依赖树 | PASS，MP3.5.17/MyBatis3.5.19/MyBatis-Spring3.0.5/Spring6.1.6 | `/tmp/recsys-mp01-dependencies.log` |
| Code Hygiene | 无阻断发现；周边格式规则、约定、差异检查通过 | `/tmp/recsys-mp01-hygiene.json` |

8 项测试覆盖：JSONB 其他键不丢失；中文/引号/SQL形似输入当普通值；顺序与重复项；首次创建及幂等更新；null 清空类别；NULL画像；非法类别形状原有降级；同 DataSource 事务回滚；会话内其他 JDBC 写入后重复查询读新值；Boot 自动配置与XML装载。其中多个断言合并在同一测试用例。

基线 4 个 skip 为 CI环境检查及三种需脚本显式启动的浏览器宿主，非本批必需测试的跳过；最终 MP15 必须显式执行三套浏览器脚本。未重新执行全仓 clean verify，不能宣称全项目迁移通过。

## 供应链与限制

新增组件许可证根据 Maven 发布 POM 核对，MP/MyBatis/MyBatis-Spring 为 Apache-2.0。OSV 对8个MP组件及MyBatis3.5.19/MyBatis-Spring3.0.5共10个坐标的查询未返回已知漏洞公告，结果存 `/tmp/recsys-mybatis-osv.json`；只表示查询时对应坐标的已知公告结果，不代表全仓无漏洞。

仓库未配置统一 formatter 或独立 Java 静态分析；沿用周边风格和 Code Hygiene，不引入新格式化工具。Code Hygiene 完成级别带上述限制。

API/数据库结构/消息/Redis/业务：UNCHANGED。45个原SQL资源及8个原用户文件保持内容。无生产/共享库操作，无新部署单元。

## 后续

MP01 DONE/PASS，Git交付事实随后记录于 PROGRESS_STATE。下一片 MP02a：先补物品 CRUD/本地副本/数组/缓存失效/回滚特征测试，再迁移；MP02b保留既有FTS测试并扩充目录读取路径。
