# ADV-UI-02 实施证据

## 02A CPC／OCPC

已实现搜索页显式模式、新交付与原操作恢复、实际可见观测和点击、原回执查询与独立当前结算。资格/回执/操作 ID 为字符串；金额六位字符串，零元捕获必须有账本。新队列独立于旧 CPM/自然队列，限制 100 条、512 KiB、1 小时、并发 2、自动 6 轮；CLICK 观测固定 0/0/false。身份撤销立即中止并清除证明，确认/过期清除持久化票据；刷新先 GET 原 ID。

- 8 项窄单测：丢 ACK/刷新/404 原负载恢复、双条件分键、原 WAITING 后零元实收、旧版本不覆盖、身份撤销、自动上限/容量/并发、非法存储、大整数、跨模式与串对象响应拒绝。
- 隔离宿主：Testcontainers PostgreSQL，生产控制器/Mapper/事务，独立数据库种子，真实 gRPC 或 in-process；测试 JWT 为本机签发适配，不证明生产登录。浏览器测试不拦截正常资金 HTTP；丢 ACK 与 403 注入明确仅模拟网络/展示边界。
- gRPC 三项浏览器 PASS：两条件正反序、零元账本、丢 ACK 原查询、刷新不重送、原交付冻结、历史打开/键盘关闭、身份切换、权限错误和不回退 legacy。数据库核对每次捕获一条 ledger/outbox、CPM 零污染及总扣费等于账本。
- 视觉：实际查看桌面 1280×900 和 390px 长页截图，沿项目 AntD 蓝白主题；窄屏表格仅容器内横向滚动，长 ID 换行，页面整体宽度 390。证据位于 `recsys-ad-serving/target/actions-evidence-{grpc,in-process}/`（本机构建产物，不提交票据）。trace 关闭，不录 HAR/网络 body。
- 首轮发现与修正：新模式未进入 AppLayout 的窄屏 Sandbox 条件，顶栏重复输入导致横向溢出；补最小开关 wiring，复测宽度通过。独占 in-process 宿主 Spring 注入覆盖手动 fixture provider，补真实 preparation/settlement Bean；不改产品后端。

当前任务全部完成，最终CI与Docker见RESULT。本文件中的 fixture PASS 不代表共享 Docker 已启用新能力。

## 02B CPA

- 新业务事件严格使用 `/api/advertiser/{advertiserId}/sandbox-conversions/{eventId}`，可见使用 conversion-impressions；不回退旧转换接口。统一队列保留原时间和证明，手工证明最多五分钟，未解码推断权限。
- 新角色scope独立于ADMIN展示scope；原广告管理权限查询失败仍可显示CPA入口，业务接口自行严格判权；不以旧disabled/shadow权限快照决定成功。
- API返回关联、六位金额、零元/未知、精简/完整结算、首次决定与最新实收分开校验。两模式各5浏览器、238前端测试、typecheck/build PASS，详见 RESULT。
- 修正表单提交后焦点丢失造成Escape无法可靠关闭，以及加载图标影响查询按钮可访问名称的问题；错误后查询必须实际收到恢复200。

## 02C 回归与运行准备

完整资金链426项/74类、旧CPM4+自然5+Feed5、相邻页面与默认关闭16项浏览器均PASS；前端238单测、typecheck、默认与启用构建通过。独占真实SpiceDB验证已完成，两次最终脚本执行均验证owner/editor、跨主体、撤权和503，不修改共享关系。具体日志与版本见 RESULT。

构建/运行边界：Dockerfile新增两个默认false的构建参数，排除真实.env和测试结果；只更新既有console服务，legacy认证/9095端口及同源gateway保持。新CI job按in-process/grpc矩阵独立运行，上传范围仅显式遮挡证明的PNG；无trace/HAR或自动错误快照上传。
