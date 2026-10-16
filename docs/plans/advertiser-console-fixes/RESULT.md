# ADV-UI-01 验证与交付记录

日期：2026-10-16。范围见 [PLAN](PLAN.md)。五项既有广告后台修复已经实现，企业可靠性 S13B～S25 仍暂停。

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

本机日志：`/tmp/recsys-advertiser-ui-{verify,frontend-final,browser,build,typecheck-final}.log`。截图：`.dev/advertiser-ui/browser/`。远程 CI 会执行全部原有五门禁，并在浏览器恢复门禁中加入本次 4 项验收。

## 审查结论与边界

代码/契约自查已覆盖所有广告管理路由和 ID 写路径、真实查询字段、错误分支、重复提交、权限缓存隔离及既有消费者兼容。无新增依赖、表结构、SQL 拼接或基础设施。安全及数据库语义通过具体场景验证；权限 SDK 在单测中为替身，不把这类测试当作真实 SpiceDB 集成验收。本机沿用原有权限模式，不声称因此开启了 enforce。

初轮测试中的 XML 内容协商、隐藏弹窗重复文本断言、局部 `bid` 类型误改均已修正。最终通过不依赖跳过本次测试或放宽业务断言。既有 jsdom/AntD/React Router 提示不影响断言；统一前端 formatter、全仓库依赖升级不在本次范围。

## 发布与回退

仅交付 ADV-UI-01 相关文件，使用 `fix/advertiser-console-workflows` 分支，正常合并并推送 `main`。用户原有 runtime/文档改动和 `console/test-results/` 不纳入提交；根 `CODEX_PROGRESS.md` 只保留本机交接，暂停任务原文不变。

精确提交的 GitHub CI 五门禁全部通过后，更新本机 Docker 的 advertiser 与 console，后端先行。镜像携带完整 revision，使用不可变 image ID 启动。原有其他服务、认证模式、资金开关和共享数据保持现状；无生产部署。

若新版服务健康或 smoke 失败，停止继续发布，按本任务保存的 `rollback.compose.json` 回到此前稳定镜像并复查；无数据库迁移，镜像回退不能撤销已经提交的业务操作。

交付后实际 SHA、CI、镜像及健康/smoke 证据写入 `.dev/deployments/adv-ui-01-<SHA>/` 和根交接。本文件是发布前验证记录，不提前声明 CI 或 Docker 已完成。
