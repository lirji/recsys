# ER-S01：预算单位与报价展示验证

日期 2026-10-15；实施分支 `fix/enterprise-budget-display`，基线 `eca38650`。正式契约为 CONTRACTS C01；本文件随相同变更提交。没有数据库迁移或真实收费切换。

## 行为与影响

- advertiser 读取旧 Redis 整数分，转换为元；非法/负数/缺失/读取失败返回未知，不伪造零消费和满额预算。DTO 两个消费字段可空，仓库全部直接消费者同步处理。
- 列表和详情明确“计数估计·元”，未知显示待确认且不画零进度；详情沿用现有布局，在窄屏改为单列以容纳标签。
- 搜索广告表、图、漏斗、重放和历史对比把报价与实扣分开。按点击/曝光/转化标注单位，取消报价求和为实收，以及排序分与报价相减/比较的价差提示。
- 没有修改旧收费 writer、日期算法、既有生产开关或报表账务权威；新持久化账本仍是后续切片。

兼容注意：`spentToday/remainingBudget` 的未知值从伪零改为 null；仓库前端已经适配。其他严格 number 消费者升级前须支持 null/缺失。本轮只交付代码，不进行远程部署。

## 验证证据

| 检查 | 结果与范围 |
|---|---|
| Maven 定向编译/测试 | `mvn -B -pl recsys-advertiser -am test -Dtest=AdvertiserBudgetViewTest,AdvertiserAuthzTest -Dsurefire.failIfNoSpecifiedTests=false`：21 tests，0 failure/error/skipped |
| 前端类型与构建 | `npm run typecheck`、`VITE_AUTH_MODE=legacy npm run build`：PASS |
| 前端业务单测 | `npm run test:run -- src/utils/__tests__/adPricing.test.ts src/components/funnel/__tests__/derive.test.tsx`：9 tests PASS，含混合计费单位与缺失报价 |
| 静态检查 | `npm run lint`：0 error，15 个仓库既有 warning；新 helper/test/E2E 的 Prettier check PASS；旧文件保持周边风格，无全仓格式化 |
| 浏览器 | `npm run test:e2e -- budget-display.spec.ts`：验证列表→详情、未知状态、桌面/390px 窄屏、报价图和定价重放；最终结果见下方 |
| Code Hygiene | 根目录 gate：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；限制是根 Maven 工程未配置 formatter，保留源码既有风格；前端静态与新增文件格式检查已执行 |
| 修改范围 | 无未授权依赖或中间件变更；既有六个用户文件保护；diff --check PASS |

后端测试使用确定性 Redis/目录读取替身证明单位/状态，不声称证明账务事务。浏览器只拦截 C01 约定 API 夹具，阻断所有写请求，不发送真实账户事件；S02 开始必须使用真实 PG 验证事务/并发。

## 浏览器观察与修正

桌面已知计数显示 1.25 / 98.75，未知行无进度条；详情可通过列表名称进入。首次窄屏截图暴露长标签在双列布局换行过密，已将该详情改为响应式单列；复核使用现有菜单收起能力，不改导航架构。E2E 曾在切换窄屏后重复寻找“收起菜单”超时（现有响应式壳层已自动收起）；测试调整为先显式收起再切换视口，未放宽业务断言。

截图生成于 `console/test-results/budget-display-*/`：`budget-desktop.png`、`budget-detail-mobile.png`、`quotes-desktop.png`；为可重复生成的忽略测试产物，不作为正式数据。

最终浏览器复核：2 tests PASS（0 skipped）；已实际查看桌面列表、窄屏详情及报价/重放截图，金额和待确认状态可读，报价按自身单位展示。窄屏收起动画等待保留 1px 边框，图表等待首帧动画结束后截图；没有把中间动画帧作为最终价格证据。

## 下一步

完成本片交付后继续 ER-S02/ER-S02A：冻结预算命令/金额协议，明确 adDb 事务管理器和版本迁移，验证账户版本及预算调减/并发。整个五目标计划仍未完成。
