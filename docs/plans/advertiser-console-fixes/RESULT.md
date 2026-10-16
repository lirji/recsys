# ADV-UI-01 验证与交付记录

日期：2026-10-16。范围见 [PLAN](PLAN.md)。状态：DONE / PASS，五项既有广告后台修复、CI、Git 交付及本机 Docker 验收均已完成。企业可靠性 S13B～S25 仍暂停。

## 可观察结果

- 广告主、广告、创意、竞价词、报表的 ID 在浏览器内使用字符串。HTTP 保留旧数字字段，并新增精确 `*Text` 字段；缺少精确文本的不安全整数会被拒绝，避免错改/错删。
- 清空定向显式发送 `audienceId: "0"`，后端验证与既有服务语义一致；null/省略保持，正数绑定，负数拒绝。itemId、audienceId 使用文本输入，不经浮点取整。
- 广告列表/详情展示真实审核状态，详情展示审核说明。缺字段显示未知；启停状态独立呈现，不伪造已投放步骤。
- 平台/广告主权限查询复用现有守卫，普通拒绝与依赖故障分开；按钮依据当前身份、对象及有效权限启用。权限刷新/失败/身份切换关闭写入口，写接口仍自行授权。
- 编辑、创建、驳回失败保留输入；写入有重复提交保护。创意/竞价词加载错误可重试，成功写入刷新本列表和关联广告信息。

## 验证证据

| 检查 | 结果与范围 |
| --- | --- |
| `mvn -B -pl recsys-advertiser -am verify` | BUILD SUCCESS，38 类 / 202 项，0 失败、0 错误、1 项原有 CI-only 探针跳过；覆盖率门禁通过 |
| 真实隔离 PostgreSQL / ShardingSphere | HTTP 序列化精确大 ID、原数字字段兼容、精确创意更新、定向 0/null/正数/负数、审核原因持久化与列表/详情读取 |
| 后端权限 | enforce 拒绝/故障、权限快照不授权后续写入或跨对象、disabled/shadow 有效策略保持 |
| 前端全量单测 | 34 文件 / 225 项通过；包含身份/对象切换、精确请求路径、权限失败重试、撤权后禁止再次创建、失败保留草稿 |
| 浏览器专项与相邻回归 | 17 项通过（本次 4、预算 2、公共错误 9、数据质量 2）；写请求在测试边界隔离，不改共享账户 |
| 桌面 / 390px 窄屏 | 已检查截图，无页面横向溢出；宽表在表内滚动，审核与操作可用 |
| TypeScript / Vite | 最终 `npm run typecheck` 与构建通过 |
| changed-files ESLint / Prettier / diff | 通过，无新增 lint 告警 |
| 持久化边界守卫 | 607 production Java / 90 Mapper XML / 45 historical SQL，通过 |
| 错误码生成与公开错误守卫 | 257 项目录与生成物一致，公开错误透传守卫通过 |

本机日志：`/tmp/recsys-advertiser-ui-{verify,frontend-final,browser,build,typecheck-final}.log`。截图：`.dev/advertiser-ui/browser/`。远程 CI 已执行全部原有五门禁，浏览器恢复门禁包含本次 4 项验收；精确提交与运行链接见下文。

## 审查结论与边界

代码/契约自查已覆盖所有广告管理路由和 ID 写路径、真实查询字段、错误分支、重复提交、权限缓存隔离及既有消费者兼容。无新增依赖、表结构、SQL 拼接或基础设施。安全及数据库语义通过具体场景验证；权限 SDK 在单测中为替身，不把这类测试当作真实 SpiceDB 集成验收。本机沿用原有权限模式，不声称因此开启了 enforce。

初轮测试中的 XML 内容协商、隐藏弹窗重复文本断言、局部 `bid` 类型误改均已修正。最终通过不依赖跳过本次测试或放宽业务断言。既有 jsdom/AntD/React Router 提示不影响断言；统一前端 formatter、全仓库依赖升级不在本次范围。

## 发布与回退

实现提交 `d787dda06268b7c06e11de4d845840b6c4c94091`（`fix(advertiser): 修复管理后台精确标识与操作闭环`，41 个文件）已从 `fix/advertiser-console-workflows` 正常 ff 合并并推送 `origin/main`。用户原有 runtime/文档改动和 `console/test-results/` 未纳入提交；根 `CODEX_PROGRESS.md` 只保留本机交接，暂停任务原文不变。文档归档提交与已部署源码 SHA 分开记录，不声称文档更新重新发布了应用。

| 发布检查 | 实际结果 |
| --- | --- |
| [主 CI 38835451492](https://github.com/lirji/recsys/actions/runs/38835451492) | 对应上述完整 SHA；build、security-scan、money-chain-gate、sandbox-exposure-browser-gate、natural-exposure-gate 五项全部 SUCCESS |
| [前端 CI 38835451341](https://github.com/lirji/recsys/actions/runs/38835451341) | 对应相同 SHA，build SUCCESS |
| 部署范围与时间 | 现有本机 Docker `desktop-linux / recsys`；先 advertiser、后 console，于 2026-10-16 09:49:42 +08:00 完成；无生产部署 |
| 镜像绑定 | revision 为上述完整 SHA，使用不可变 image ID 启动；只更新这两个服务的 latest 别名 |
| 健康检查 | gateway、rec-engine、ad-serving、behavior、advertiser、console-api、content、user 八个后端均 UP |
| HTTP smoke / 真实页面 | PASS；admin/advertiser 读取权限与列表/详情通过，浏览器访问 `http://localhost:9095/advertiser/ad/1` |
| 精确 ID / 审核验收 | 创意 ID 为 `1306381397513998336`；真实状态 approved，已通过的审核按钮不可重复操作；保存前后截图 |
| 数据及其他服务 | 部署验收无共享业务写入，其他应用服务未更新；沿用 legacy 认证、原权限模式和原资金开关 |
| 回退 | 未触发；旧稳定镜像及 `rollback.compose.json` 已保留。无数据库迁移，镜像回退不撤销已提交业务效果 |

不可变镜像：

- advertiser：`recsys-advertiser:adv-ui-01-d787dda06268`，image ID `sha256:3ad383ca74c412ce0fc9be9bd7a916535fba3578a2cf91b59debfddf33cf3ff9`；JAR SHA-256 `35b24288f13aa189ff3848e43eaecbb845b48d923f9ec67d67358eb2dd4fff09`。
- console：`recsys-console:adv-ui-01-d787dda06268`，image ID `sha256:38789156e4fd13ef043930e0e9a9e6bb567c665292cc387241d346e7dc1aa439`。

## 归档与证据入口

本文件是最终受版本控制的交付摘要，原始本机证据位于 `.dev/deployments/adv-ui-01-d787dda06268/`：

- `delivery-result.json`、`deployment-result.json`：Git/部署总结果、服务与版本范围。
- `final-ci.json`、`console-ci.json`：精确提交与 CI 各门禁结论。
- `images.json`、`verified-artifacts.json`、`images.compose.json`：镜像及构建产物绑定。
- `smoke-results.json`、`ui-result.json`、`after-ui.png`：实际 HTTP 与浏览器验收。
- `before-images.json`、`rollback.compose.json`、构建和启动日志：回退材料与执行记录。

上述 `.dev` 原始材料为本机忽略文件，未作为可跨机器获取的仓库附件；应保留，不清理。权限 SDK 替身测试不等于真实 SpiceDB/enforce 验收。新 CPC／CPA 前端尚未接入，属于用户随后要求的新任务；S13B～S25 仍按原交接暂停。
