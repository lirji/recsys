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
