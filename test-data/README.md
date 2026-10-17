# 桶对比大盘演示数据

为现有本机环境的四个实验桶补充 **100 条合成自然推荐点击**，通过真实行为接口归因，再运行真实离线作业。结果用于功能演示，不能作为真实用户实验、算法收益或显著性结论。

## 已发现的基础设施与范围

| 能力 | 当前实际配置 | 本工具动作 |
| --- | --- | --- |
| Docker | 本机 `desktop-linux`，现有 `recsys-*` 应用 | 复用运行容器与 Java 运行镜像，临时离线容器完成后退出 |
| PostgreSQL | `dev-infra-recsys-postgres-1`，数据库 `recsys`，BFF 别名 `recsys-postgres` | 查询曝光、验证点击与报表；清理仅匹配本次清单 |
| Redis | 行为服务经 `recsys-redis` 查询曝光归因 | 由真实接口读取；不灌入、不清理 Redis |
| Kafka | 项目已有；当前 `behavior.use-kafka=false`，额外发布默认关闭 | 不初始化、不发布测试消息 |
| 报表 | `ab-report` 生成 CSV，`publish-reports` 写 `eval_report`，BFF 从数据库读取 | 只发布本工具目录中的 DEMO 报表 |

配置证据：各服务 `application.yml`、`BehaviorService` / `BehaviorMapper.xml`、`AbReportJob`、`PublishReportsJob`。运行时会确认 Docker context、回环 URL、BFF 数据库别名及数据库身份；不会连接任意远程 HTTP。

## 数量与归属

按首次规划的四桶曝光数从多到少，分别增加 55、20、15、10 条点击，共 100 条。每条关联一个真实且尚未点击的 `exposure_id`，由服务端校验 user/item 并确定 bucket；不新增用户或曝光，不调用广告点击、转化、扣费接口。

合成点击的 `scene=demo-ab-board-v1`，曝光、用户、物品和分桶保持原归属；此场景标记用于识别与精确清理。报表默认跨场景统计，因此包含现有曝光和这些合成点击；不能单独按此 demo scene 计算真实实验 CTR。当前四桶各只有一个用户，曝光数量也不等同于独立用户样本量。

## 运行

先通过环境变量设置现有本机账号 `RECSYS_DEMO_USERNAME` / `RECSYS_DEMO_PASSWORD`；示例配置见 `config/test-data.env.example`，密码不写入仓库或运行清单。

```bash
bash test-data/init-test-data.sh
bash test-data/run-ab-report.sh
bash test-data/verify-test-data.sh
```

离线脚本先构建现有 offline 模块，然后在与 BFF/数据库相同的网络运行临时 Java 容器。生成文件名带 `ab-report-DEMO-` 标记，再调用 `publish-reports`，控制台可直接读取。需要本机 Docker、Python 3、Maven/JDK 21（含 javac）；`psql` 复用已有 PostgreSQL 容器。

现有 `OfflineApplication` 全量扫描会把 Web 安全链装进无 Web 上下文，出现缺少 `mvcHandlerMappingIntrospector` 的启动错误。本工具的 `AbBoardReportRunner` 仅装配正式 `AbReportJob` / `PublishReportsJob`、原 Mapper XML 与会话工厂；未复制统计算法、未关闭在线安全、未修改产品启动类。普通离线 CLI 的这个问题尚未在本任务中修复。

`.dev/test-data/ab-board-v1/` 保存固定曝光清单、实际点击 ID、作业日志、CSV 与验证结果，不含认证令牌。不要丢弃该目录后盲目重跑：清单缺失但标记数据仍存在时工具会停止。重复 init 复用同一组曝光幂等键；已发布报表复用并核对，不重复生成。

## 清理

```bash
# 只预览；不会删除数据。
bash test-data/cleanup-test-data.sh
# 确认需要移除本次演示后才执行。
bash test-data/cleanup-test-data.sh --apply
```

清理只处理保存清单内的 CLICK、演示 scene 和本次 DEMO 报表；不删除原曝光、其他点击、其他报表或本地证据。不重置进程内累计指标。清理后若需要重新初始化，应先核对清理结果及保留的旧报表记录，不把旧验证结果作为新一轮证据。

验证分为 `DATA_VERIFY`（100 条归因一致且原曝光未变）和 `APP_VERIFY`（真实报表索引、CSV 内容经 BFF 读取）。浏览器大盘需要刷新以更新一分钟的查询缓存。

## 补充四个用户样本

追加场景 `more-users` 使用独立清单 `.dev/test-data/ab-board-v2/`，保留上述 v1 数据和报表。新增4个演示用户，每人固定对应一个已有桶、20个不同物品曝光，共80曝光；四桶分别补4、6、7、3个点击，共20点击。普通实体4条、核心行为100条。分桶是演示 fixture 指定的，不用于检验在线分桶算法或实验收益。

用户与曝光 fixture 写入现有 PostgreSQL：用户 profile 和行为 scene 均带 `demo-ab-board-v2` 标记；固定曝光 ID、事务锁和存在检查保证本工具重复初始化不重复写曝光。点击仍通过真实 `/api/behavior`，由服务端关联曝光确定桶。初始化不覆盖已存在的用户/曝光，画像被修改或清单缺失时停止。没有新的基础设施或产品代码改动。

```bash
bash test-data/init-test-data.sh --scenario more-users
bash test-data/run-ab-report.sh --scenario more-users
bash test-data/verify-test-data.sh --scenario more-users
bash test-data/cleanup-test-data.sh --scenario more-users  # 只预览
```

运行前仍须提供上述本机账号环境变量。第二次 init 复用这4个用户和同一组曝光，不再增加样本；第二次 report 复用该批次已发布文件。清理加 `--apply` 才执行，仅匹配本批次100条行为、4个标记用户和1份DEMO报表；用户存在后续行为/向量时保留用户，画像改变时停止清理。不会删除v1数据或原报表。

## 补充兴趣画像并验证真实推荐

沿用4个演示用户，通过 `/api/user/{id}/interests` 写入不同偏好；当前运行配置 `USER_SERVING_MODE=grpc`，实际经过 rec-engine → user-service → PostgreSQL。类别来自已有 `item.category`，不创建物品或用户，也不生成用户向量。

| 用户 ID | 演示兴趣 |
| --- | --- |
| 2026101701 | Action、Sci-Fi（动作科幻） |
| 2026101702 | Comedy、Romance（喜剧爱情） |
| 2026101703 | Animation、Children（动画亲子） |
| 2026101704 | Documentary、Drama（纪实剧情） |

```bash
# 仍需上述本机账号环境变量。
python3 test-data/user-interests-demo.py init
python3 test-data/user-interests-demo.py verify
python3 test-data/user-interests-demo.py cleanup  # 默认预览
```

数量：4次画像更新；每位用户设置前后各1次真实推荐、每次5条，共40条服务自动曝光，无新增点击。请求使用 `explain=true` 旁路推荐缓存、scene=`demo-user-interests-v1`，未强制召回路或桶。固定请求结果、原画像、曝光 ID 和验证保存在 `.dev/test-data/user-interests-v1/`；重复 init/verify 复用响应，避免把验证变成反复灌曝光。响应未知时停止并要求先核对结果，不自动重发。

验证覆盖数据库、兴趣API、用户360，以及TAG原始候选增加、最终TAG推荐类目匹配和曝光落库。为保留工具幂等性，已确认的画像同步至原v2清单的逐用户 `profiles` 字段；原v2的init/verify也能继续运行。

在控制台「冷启动兴趣」或「用户360」将顶部 userId 切到上述ID即可查看。原用户1没有被修改。画像可以影响TAG召回，但最终列表仍会融合热门/冷启动等渠道，不保证全部内容都属于所选兴趣。

清理仅在显式加 `--apply` 后恢复4份原画像并删除清单中的本场景40条曝光；不会删用户、原点击、原曝光或报表。画像被外部修改、曝光与清单不符或出现后续点击时停止。Redis曝光归因由服务按原2小时TTL到期，不清空共享Redis；进程累计指标不回滚。先恢复本兴趣场景，再清理v2场景。初始化不重算已发布的历史AB报表，历史报表仍表示当时快照。
