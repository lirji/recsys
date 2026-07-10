# 前端系统总览页技术方案

## 目标

在现有 `console/` 前端中新增一个“系统总览 / 项目地图”页面，作为 recsys 项目的默认入口页。页面用于内部演示、调试和排障，集中展示模块、服务、链路、API、健康状态和启动方式。

## 非目标

- 不新建第二个前端工程。
- 不引入登录、权限、租户、审计等生产运营后台能力。
- 不在总览页提供启动、停止、删除、训练、数据清空等高风险写操作。
- 不让浏览器直连 `8081/8082/8083/...` 等内部服务端口。
- 不运行时解析 `pom.xml` 或 YAML 作为首版核心逻辑。
- 不引入新的前端图表、拓扑或低代码依赖。

## 决策记录

| 方案 | 结论 | 原因 |
|---|---|---|
| 纯前端静态页 | 不采用 | 实现快，但无法展示真实健康状态，端口/API 容易漂移 |
| 前端直连各服务健康接口 | 不采用 | 有 CORS、部署差异、内部端口和 Actuator 暴露风险 |
| `recsys-console` BFF + 前端总览页 | 采用 | 复用现有 `/api -> gateway -> recsys-console` 架构，可统一降级、脱敏和环境适配 |

## 当前约束

- 前端位于 `console/`，技术栈为 React 18 + TypeScript + Vite + Ant Design + React Query。
- 前端 API 统一走相对路径 `/api`，开发阶段由 Vite 代理到 `recsys-gateway:8080`。
- 网关已覆盖 `/api/console/**` 到 `recsys-console:8090`，新增 `/api/console/system/**` 不需要改网关。
- 当前工作区已有广告、广告投放、离线作业相关未提交改动，本任务不得触碰这些文件。
- `console/dist` 是构建产物，不纳入本功能改动。

## 路由与页面流

```text
/              -> /overview
/*             -> /overview
/overview      -> 系统总览
/recommend     -> 推荐调试
/search        -> 搜索调试
/search-ads    -> 搜索广告调试
/feed          -> 混排 Feed
/query         -> Query 理解
/experiment    -> 实验管理
/user-interests -> 冷启动兴趣
/advertiser    -> 广告主后台
/reports       -> 离线报表
```

菜单结构：

```text
项目
- 系统总览

在线调试台
- 推荐
- 搜索
- 搜索广告
- 混排 Feed
- Query 理解
- 实验管理
- 冷启动兴趣

广告主后台
- 广告主 / 广告

离线报表
- 评测 / 报表
```

## 页面信息架构

系统总览页按诊断优先级组织：

1. 项目简介：项目名、说明、技术栈、手动刷新入口。
2. 健康概览：模块总数、可运行服务、领域模块、在线服务。
3. 服务健康：状态表、最后检查时间、15 秒自动刷新、局部错误。
4. 核心链路：推荐、搜索、广告、Feed、行为、离线、实时特征。
5. 模块地图：模块名、类型、职责、端口、gRPC、网关路径、前端入口。
6. API 目录：方法、路径、所属服务、说明、页面入口。
7. 启动命令：前端、核心服务、内部服务、离线与实时。

## 组件树

首版可以保留在一个页面文件内；如果页面继续膨胀，再拆到 `console/src/components/system/`。

```text
ProjectOverview
  OverviewHeader
  MetricCards
  HealthTable
  SystemLinksGrid
  ModuleTable
  ApiTable
  CommandGroups
```

## 前后端 API 契约

接口均由 `recsys-console` 提供，并通过网关访问。

```text
GET /api/console/system/overview
GET /api/console/system/modules
GET /api/console/system/health
GET /api/console/system/apis
GET /api/console/system/commands
```

契约边界：

- `overview` 只返回静态 manifest，不触发健康探测。
- `modules/apis/commands` 返回静态 manifest。
- `health` 独立动态探测，前端 15 秒刷新。
- `checkedAt` 为毫秒时间戳。
- `url` 可为空，job 类服务不提供 URL。
- `message` 只返回脱敏说明，不返回 Actuator details。
- 空数组表示暂无元数据，不表示请求失败。
- 单个服务探测失败只影响该服务状态，不能导致整个 `/health` 失败。

健康状态枚举：

| 状态 | 含义 |
|---|---|
| `UP` | 服务健康端点可达且返回健康 |
| `DOWN` | 服务健康端点不可达、超时或返回异常 |
| `UNKNOWN` | 未配置探测或状态无法判断 |
| `JOB_ONLY` | 离线作业类模块，不按 HTTP 服务探测 |
| `LOCAL_CLUSTER_JOB` | 本地/集群作业类模块，不按 HTTP 服务探测 |

健康探测要求：

- 探测目标通过配置管理，默认本地端口，部署时可覆盖。
- 探测短超时，避免拖慢页面。
- 探测并行执行，避免多个服务未启动时串行累计等待。
- 不向前端透出 Actuator 原始 details。

## 状态与边界情况

- 首次加载：显示 `Spin`。
- 静态 manifest 失败：显示全页 `Alert` 和重试按钮。
- 健康接口失败：只在健康区显示错误和重试按钮，模块/API/命令仍展示。
- 后台刷新：保留旧数据，健康区显示刷新中或最后检查时间。
- 所有下游服务未启动：只要 BFF 可达，页面仍展示，多数状态为 `DOWN`。
- `recsys-offline`、`recsys-streaming`：显示 `JOB_ONLY` / `LOCAL_CLUSTER_JOB`，不算失败。
- `frontendPath` 为空：不生成无效链接。
- 宽表格在移动端必须横向滚动。

## 文件级改动

前端：

- `console/src/api/types.ts`：新增系统总览 DTO 镜像。
- `console/src/api/system.ts`：封装 `/api/console/system/**`。
- `console/src/pages/project/ProjectOverview.tsx`：新增总览页。
- `console/src/router.tsx`：新增 `/overview`，`/` 和 `*` 跳转 `/overview`。
- `console/src/components/AppLayout.tsx`：新增“项目 / 系统总览”菜单。

后端：

- `recsys-console/src/main/java/com/recsys/console/system/SystemOverview.java`：系统总览 record 契约。
- `recsys-console/src/main/java/com/recsys/console/system/SystemOverviewService.java`：静态 manifest。
- `recsys-console/src/main/java/com/recsys/console/system/SystemHealthProperties.java`：健康探测配置。
- `recsys-console/src/main/java/com/recsys/console/system/SystemHealthService.java`：并行健康探测。
- `recsys-console/src/main/java/com/recsys/console/system/SystemController.java`：BFF 控制器。
- `recsys-console/src/main/resources/application.yml`：默认健康探测目标。
- `recsys-console/src/test/...`：后端契约和健康探测测试。

## 依赖顺序实施步骤

1. 固化文档和契约。
2. 后端拆分静态 manifest 与健康探测。
3. 健康探测改为配置化并行探测。
4. 补后端 controller/service 测试。
5. 前端拆分静态 query 与 health query。
6. 前端补重试、空态、横向滚动和健康刷新提示。
7. 运行 `mvn -pl recsys-console -am test`。
8. 运行 `cd console && npm run build`。
9. 检查 `git status`，确认未触碰广告/离线脏改动，未纳入 `console/dist`。

## 测试策略

- 后端：
  - `mvn -pl recsys-console -am test`
  - controller 测试覆盖 5 个 GET 接口。
  - health service 测试覆盖 `UP`、连接失败、空 targets、job 状态、配置缺省。
- 前端：
  - `cd console && npm run build`
  - 类型检查覆盖 TS 契约。
  - 手工检查 `/overview` 加载、错误、健康刷新、跳转和表格横滚。
- 验证时不要求所有服务 `UP`；服务未启动时能降级展示即通过。

## 验收标准

- `/overview` 能展示项目简介、健康、链路、模块、API、命令。
- `/` 和未知路由默认进入 `/overview`。
- 前端不直连各服务端口，只请求 `/api/console/system/**`。
- `overview` 不触发健康探测；健康状态单独 15 秒刷新。
- 部分服务未启动时页面仍可用。
- `JOB_ONLY`、`LOCAL_CLUSTER_JOB` 不渲染为失败。
- 宽表格在移动端不溢出页面。
- 现有推荐、搜索、广告、报表页面不受影响。
- 前端 build 和后端测试通过。

## 风险与回滚

风险：

- 健康目标配置不匹配部署环境会误报 `DOWN`。
- 服务未暴露 `/actuator/health` 时可能误判不可用。
- 静态 manifest 后续可能和真实模块漂移。
- 总览页信息过多，移动端阅读成本高。

回滚：

- 前端可移除 `/overview` 路由和菜单，恢复 `/` 跳转 `/recommend`。
- 后端可移除 `recsys-console` 的 `system` 包，不影响现有 `/api/console/report/**`。
- 若健康探测导致性能问题，可临时保留静态 manifest，只关闭 `/health` 动态探测。
- 不回滚或覆盖广告、广告投放、离线作业等非本任务改动。

## 开放问题

- 是否需要纳入 Redis、Postgres、Kafka、Nacos 的健康状态。
- 是否需要支持 local/dev/staging 环境切换。
- 是否需要后续加入权限、登录和操作审计。
