# MyBatis-Plus 技术接入决策

状态：已批准；MP01 已通过用户与推荐宿主真实集成并写入 POM，其他路径按计划继续验证。

| 决策 | 当前/约束 | 选择及理由 | 代价与兼容门禁 | 阶段 |
|---|---|---|---|---|
| Java / Spring Boot | 21 / 3.2.5 | 保持 | 不用升级 Spring 解决未查明的问题 | 已存在 |
| PG / ShardingSphere | PG 驱动 42.7.3 / ShardingSphere 5.5.0 | 保持现有数据库和路由 | 所有特殊 SQL、分片 ID、类型及事务真实验证 | 已存在 |
| 持久框架 | 用户明确要求 MP | INTEGRATE MyBatis-Plus，Boot 宿主用 boot3 starter | BOM 统一版本；不同时引入原 MyBatis Starter；不在纯契约模块添加数据库自动配置 | MP01 |
| 候选版本 | 官方安装页当前示例 3.5.17 | 首选验证 3.5.17 的 Boot3 分支 | MP01已冻结3.5.17并验证Boot3.2.5/依赖树/PG试点；Flink与真实分片待对应工作包 | MP01 |
| 公共代码 | platform 为 web starter；common/proto 为契约 | BUILD 一个小型 recsys-persistence，仅复用实际共用 PG 类型处理 | 不依赖业务模块、不含 web/安全/Boot 启动器、不创建全局数据源，不建万能 Repository | MP01 |
| 复杂 SQL | PG CTE、RETURNING、行锁、全文、向量 | 本模块明确 Mapper XML | 保留原查询含义，额外维护 XML 与类型映射 | 各批 |
| 插件 | 既有显式 limit/offset、版本条件 | 初始不开自动分页/乐观锁/逻辑删除/租户等改变 SQL 的全局拦截器 | 需要 MP 分页 API 的具体路径再验证 PG 方言及 JSqlParser 依赖；不使资金 SQL 被隐式改写 | 按实需 |
| Flink | 非 Boot shade jar | 非 Boot MP 核心/所需模块，独立会话生命周期 | 校验 XML 打包和 session 关闭，不引入 Boot 应用上下文 | MP14 |
| 表结构/迁移 | 已有版本 SQL + 摘要校验 | REUSE_EXISTING；运行时 DDL 提取资源 | 不引入第二迁移框架、不改历史脚本、无新业务表 | 各批 |
| 测试与中间件 | JUnit、Testcontainers、PG、Kafka、浏览器门禁 | REUSE_EXISTING | 隔离测试；不操作共享 dev_infra 数据 | MP00 起 |

MyBatis-Plus 的 Boot3 starter 名称及避免重复引入 MyBatis starter 的规则来自[官方安装说明](https://baomidou.com/getting-started/install/)。分页插件的 PostgreSQL 支持及独立解析器模块见[官方分页说明](https://baomidou.com/plugins/pagination/)。版本取自检索结果，最终可用性以本项目兼容门禁为准。

## 依赖验证清单

1. 根 dependencyManagement 固定 MP BOM/版本；有 DB 的模块才声明所需依赖，gateway/common/ad-common/proto/纯计算模块不为凑“全项目”而直接加 Starter。
2. 核对 MP、MyBatis、MyBatis-Spring、Spring、JSqlParser 的解析版本，排除重复 Starter 和与 ShardingSphere/宿主已有依赖的冲突。不得通过全仓无关升级掩盖冲突。
3. 校验依赖许可证和已知漏洞；复用现有扫描工具，准确记录来源、结果和例外，未核验不得写“无漏洞”。
4. Boot 单/多数据源启动；MP BaseMapper 与 XML 都真实执行；共用数据源事务回滚、生成键和 PG 类型 round-trip。
5. Flink 保持原 shade 生命周期，最终 jar 检查资源、服务发现、无无意 Boot/web 启动依赖。

## 复杂度预算

新增主要第三方仅 MP 及必要传递依赖；新增技术模块仅为多个业务模块已经存在的数组/JSONB/向量映射复用。它不持有业务 Mapper、业务表或连接参数。若首批证据证明共享模块没有实际复用价值，先精简设计再确认变更，不保留空平台。

不引入动态数据源路由框架：现有 DataSource 及 owner 事务已经显式定义，添加另一套路由会增加切换和事务绑定的不确定性。保持每源一套 factory/template 与精确扫描，配真实路由测试。

失败分支：首选 MP 版本不兼容时定位具体传递依赖，在保持 Boot 3.2.5/Java21 的边界内选可用稳定补丁版本并记录证据；如果必须改变运行栈或协议才能推进，再提交明确技术取舍。不能因版本尚未实测把设计状态标为已接入。
