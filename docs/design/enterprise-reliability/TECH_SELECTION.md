# 技术与方案取舍记录

状态：DRAFT；不是新增依赖授权。沿用项目现有 PostgreSQL、Redis、Kafka、Java/Spring、React/Ant Design/TanStack Query/Axios、Vitest/Playwright。公共组件优先复用 dev_infra 的隔离资源，不修改共享实例数据或顺手升级依赖。

| 决策 | 备选 | 草案选择与原因 | 成本/边界 |
|---|---|---|---|
| 曝光口径 | 服务端响应即曝光；客户端可见回执；第三方验证 | **用户已选可见回执 + 服务端资格/签名/幂等** | 终端测量可被伪造；第三方反作弊、终端可信度仍是独立建设项 |
| 预算约束 | Redis 节奏/允许超投；持久化硬预占 | **用户已选硬上限预占**；建议 PG 条件更新/行锁 + 业务唯一约束 | 较高争用和未决预算占用；CPA 必须评估长窗口可用额度 |
| 金额表达 | double；整分；精确小数/更小定点 | 推荐 BigDecimal + PG NUMERIC + JSON 字符串，精度待冻结 | proto 可新增字符串/结构化 Money，不能静默改变旧 double 单位 |
| 可靠事件 | 同步双写；应用 Outbox；CDC | 推荐同库事务 Outbox，复用 Kafka；现状与业务键最易直接关联 | Relay/清理/重放需运维；分片 Outbox 不能写在另一个公共库。CDC 作为吞吐实测后的替代，不同时新增 |
| 消费效果 | 仅靠 offset；Inbox + 版本化投影 | 推荐 Inbox/业务唯一键 + 聚合版本 + 删除墓碑 | 至少一次传输，外部副作用需自己的幂等；Redis 另走可靠派生刷新 |
| 服务边界 | 新建账务微服务；既有 ad owner 内模块 | 推荐现有 `recsys-ad` 增加账务用例，由同一 ad owner 执行 | 先修逻辑和事务边界；以后是否拆服务由容量与团队 ownership 决定 |
| 前端采集 | ItemCard 内自动上报；页面显式 scope + hook/队列；新 SDK | 推荐页面显式采集，组件保持展示职责 | 四页逐步接入；避免沙盘、历史和对比产生收费 |
| 回执暂存 | 仅内存；有界会话存储；IndexedDB 离线平台 | 推荐内存 + 有界 session 恢复作为首版候选 | 不保证浏览器清存储/关闭后的补发；不存 Bearer；若业务要求重启后恢复再选 IndexedDB |
| schema 演进 | 修改初始化 SQL；版本化迁移工具/脚本 | 必须新增版本化迁移；具体工具在 D0 核对现有依赖和发布方式后冻结 | 不修改已执行迁移历史；表与每个字段有中文注释；先扩展再切换 |
| 搜索技术 | 立即引入 ES；先补 query 相关性评测 | 本轮不新增 ES | 以中文/长尾检索实测决定，不让搜索改造分散资金与事件闭环 |

PG 行锁可以串行化相关账户/预占更新，但要求正确锁序和事务边界，不能代替业务约束（[PostgreSQL 16 Explicit Locking](https://www.postgresql.org/docs/16/explicit-locking.html)）。Kafka 的传输事务不自动覆盖外部数据库（[Kafka 3.7 design](https://kafka.apache.org/37/design/design/)）。浏览器 Intersection Observer 提供元素与视口交叉观测，不能证明注意力或真人身份（[W3C Intersection Observer](https://www.w3.org/TR/intersection-observer/)）。

没有新增版本选型或依赖安装；正式实施如需新增迁移/测试依赖，先核对当前官方兼容性、维护状态、许可证和已知漏洞，并复用仓库版本管理。当前 SLO、RTO/RPO、吞吐预算不在本文件编造数值。
