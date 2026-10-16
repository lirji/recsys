# ER-CI-FIX-01：曝光浏览器预算种子跨上海午夜

本地修复与验收 **PASS**；Git交付及远程CI以本轮 `delivery.json` / `fix-ci.json` 和实际Git为准，不预先宣称远程成功。此整改是 S15B2 前的必要前置，父 S15B/S15 及 S15～S21 仍未完成。

## 范围与根因

用户本轮明确授权修改 `SandboxExposureBrowserFixtureTest.java` 和必要测试工具/验收文档；原目录分支 `fix/sandbox-exposure-budget-midnight`，基线 `13f79e89b7cb02a6e498ccd6efac32c7ed71f6f8`。不修改11项保护文件、c032ea12已修正的CPA纳秒fixture、产品资金规则、真实时钟或共享账户，不部署。

C15B设计提交 `8b695c76bc343a082d64add4775f1f0729957bc4` 的 [CI38927688429](https://github.com/lirji/recsys/actions/runs/38927688429) 保留原始failure：6门禁成功，曝光浏览器4例失败。旧宿主初始化仅创建上海当天账户；日志Spring starting在15:59:59.280Z、Tomcat ready在16:00:00.693Z、首HTTP在16:00:05.436Z，产品ReservationService按数据库接受时间选择上海次日，页面ACCOUNT_MISSING。原始日志及页面证据在 `.dev/codex-flow-reliability/C15B_CI_FINDING.md` 和 `round-347abae9/`；未重跑旧CI掩盖失败。

## 最小修改

- 曝光宿主调用已有 `ReservationRepository.databaseNow()`，与产品 `ReservationService.prepare` 共用数据库时钟来源，再按 `Asia/Shanghai` 换算种子日期。
- 当天、次日分别通过真实 `BudgetAccountService.setLimit` 创建独立100元预算，要求命令APPLIED；不迁移额度、不变更账本或预占日。脚本启动180秒、浏览器240秒及宿主等待10分钟均远小于一天，两日覆盖可跨越的午夜。
- 复查同类 `ConversionOnlineFixture`，已有基于数据库日期补齐次日账户的做法，但无可直接复用公共工具；保持该fixture和其他宿主原样，不引入通用框架或新依赖。
- 新增 `SandboxExposureBudgetSeedPostgresTest`，并纳入原 `scripts/test-sandbox-exposure.py` 同一次Maven门禁；也由现有build和money-chain收集。产品、迁移、CI配置和浏览器断言均无修改。

## 验证

| 验收 | 实际结果 | 证据（本轮目录） |
| --- | --- | --- |
| 确定性负对照：种子入口只建当天 | 4例全部以“缺失预算日”失败，0错误/跳过；证实断言能捕获原单日缺口 | midnight-red.log / midnight-red.xml |
| 最终确定性真实PG边界 | 4/4 PASS：上海午夜前1微秒、月末、年末、恰好午夜；各例独立schema，检查两日各100.000000、held/charged=0、版本1、币种/流量/广告主及2个APPLIED命令，其他日期无账户 | browser-reports/ |
| 完整 `python3 scripts/test-sandbox-exposure.py` | exit 0；5个JUnit（4边界+1宿主）及4个Chromium用例PASS，零失败/错误/跳过/重试 | browser-gate.log / browser-backend.log / browser-result.json |
| 原有真实HTTP/账本/Kafka浏览器回归 | 丢ACK后原ID恢复、刷新/历史脱敏、真实hidden/离屏计时、受控错误恢复；保留精确金额0.010001与每操作一笔账、Outbox投影及宿主最终总额一致断言 | browser-evidence/ |
| 静态检查 | persistence单测6、error目录单测10、Mapper/历史SQL边界、生成目录一致性、公开错误检查、Python AST、diff --check均PASS | 各静态log / protection-validation.json |
| 保护与源版本 | 11保护文件、旧CPA fixture、B1的23源码指纹与历史进度正文不变；3项本次源码与已验证指纹一致 | start-evidence.json / source-fingerprints.json / protection-validation.json |
| 复审 | 同Agent独立只读pass，未发现阻断；不是第二评审者 | independent-review.json |

负对照命令：`mvn -B -pl recsys-rec-engine -am test -Dtest=SandboxExposureBudgetSeedPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`。首次执行因现有Maven缓存目录沙箱写权限失败，尚未运行测试；平台审批后执行负对照。最终门禁完整执行原脚本，未用Mock替代PG/Kafka或跳过宿主。

## 证据与交付边界

运行证据 `.dev/codex-flow-reliability/round-99fbfaf6/`，全部不提交；包括源码/提交指纹、JUnit XML、后端日志、截图、保护验证、CI快照和真实Git结果。已只读核对本轮6个独占测试容器全部退出、无本地测试会话待续。保留原 `.dev/target`、用户改动与唯一worktree，不清理用户内容。

B1原本地25项新验收+16项旧回归报告已与当前23源码指纹核实一致，且B1已提交/ff合并/push，未重复实施或重跑其测试；本轮只新增本整改逻辑提交。已在本次推送前等到B1源码 [CI38933333366](https://github.com/lirji/recsys/actions/runs/38933333366) **completed/success，8个job全部success**，精确绑定13f79e89，未被concurrency取消；最终快照 `b1-ci.json`。本修复的新CI独立登记，不套用B1成功。

限制：确定性测试控制的是隔离种子入口的Instant输入，未声称整条浏览器链路实际跨壁钟午夜；最终浏览器仍使用真实数据库和时钟。未配置统一Java formatter或独立Java静态分析，未声称完成这些检查。两日种子服务于现有有界测试宿主，不是生产预算自动续费策略。

下一片 **ER-S15B2**：显式历史清单、公平续办、审计取消、旧消费者topic guard；本轮未实施，父片保持IN_PROGRESS，S22～25暂停。
