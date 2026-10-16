# 广告后台操作闭环修复（ADV-UI-01）

用户于 2026-10-16 批准执行前端盘点中的五项修复。范围为既有广告后台缺陷；不新增页面、不恢复企业可靠性 S13B～S25，不启用新资金链路。

## 执行方案

1. 后端广告主管理 DTO 保留原数字 ID，同时增加 `*Text` 字符串字段；前端 API 边界统一转为字符串 ID，路由、表单和写请求全程保真。旧服务只有安全整数时允许兼容；不安全数字拒绝继续操作。比直接修改原 JSON 字段类型多几个字段，但支持既有消费者与分阶段部署。
2. 恢复已声明的定向协议：省略/null 不变，0 清除，正数绑定；负数拒绝。表单空值显式清除，ID 输入采用纯文本及整数校验，不使用数值控件的取整/步进。
3. 通过既有 Mapper 读取 `review_status`、`review_reason` 并随 AdView 返回；列表/详情分别展示启停状态和审核状态，未审核不显示“投放中”。不编造历史步骤或可投放性。
4. 增加只读操作权限查询，由既有 AdvertiserAuthz 判断 `administrate/review/edit/manage`，界面不从 ADMIN 角色猜归属权限。disabled/shadow/enforce 沿用原策略；查询不是授权凭据，每次写操作仍由原服务端守卫判权。权限加载/失败/刷新及身份切换时关闭写入口。
5. 广告编辑、驳回仅成功后关闭，失败保留输入；写入期间阻止重复提交。创意/竞价词加载失败显示错误与重试，不当作空列表；保存后刷新关联详情/列表。

## 页面与组件

- 路由保持 `/advertiser` → `/advertiser/:id` → `/advertiser/:advId/ads` → `/advertiser/ad/:adId`，报表路径不变。
- 沿用 PageHeader、AntD Form/Modal/Table、CreativePanel、BidwordPanel；新增局部权限 hook/提示及 ID 规范化 helper。
- 后端修改 dto、AdvertiserController/Service/Authz、AdvertiserRepository 和原 Mapper XML；无表结构、依赖或中间件变更。

## 验证与回退

- DTO/HTTP 验证大整数精确文本及原字段兼容、0/null/负数校验、真实审核状态；使用既有隔离 PostgreSQL 测试核对 review_reason 查询。
- 权限覆盖只读/可编辑/可管理/审核、拒绝与依赖失败；浏览器提示不能代替后端授权。
- 前端覆盖精确 URL、表单清除、失败保留输入、重复提交、未知审核状态、列表错误/重试及角色切换。
- 全前端单测、类型/build、相关 Java 测试、SQL/错误码守卫；当前变更的 CI 必须通过。实际浏览器检查桌面和窄屏，不在共享数据上做删除或审核验收。
- 先部署兼容后端，再部署前端到现有本机 Docker；保留旧镜像 ID。无数据库迁移，代码回退不宣称撤销已提交业务操作。

## 当前状态

DONE / PASS（2026-10-16）：实现、验证、正常 Git 合并/push、精确提交 CI 及本机 Docker 验收均已完成。实现基线 `dc3bd496`，任务分支 `fix/advertiser-console-workflows`，已部署源码为 `d787dda06268b7c06e11de4d845840b6c4c94091`。最终 CI、镜像、健康与页面验收见 [RESULT](RESULT.md)。后续归档提交只补文档，不改变已验收镜像的源码版本。

用户现有 runtime/文档改动保持未暂存；根交接只更新当前任务摘要并保留暂停任务原文。新 CPC／CPA 前端接入是后续独立任务，不计入本次五项修复，也不自动恢复 S13B～S25。
