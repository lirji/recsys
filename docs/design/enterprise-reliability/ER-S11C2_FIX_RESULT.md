# ER-S11C2-R1：旧浏览器测试宿主装配修复

2026-10-15；基线f914f57184f36b17ca11302ed53686f3a3e48516；原目录独立分支fix/sandbox-browser-service-wiring。

CI38698175497的build、money-chain、natural-exposure、security成功，sandbox-exposure-browser在启动测试宿主时失败。日志明确为SandboxDeliveryController所需Qualifier("deliveryOperationService")没有对应Bean；旧fixture自建@Bean默认名为operations。上一版本clean verify跳过此专用fixture，未发现该装配问题。

修复仅将SandboxExposureBrowserFixtureTest的Bean显式命名为deliveryOperationService，与生产配置一致，保留生产端显式Qualifier以及所有浏览器/资金断言。无产品逻辑、公开协议、依赖或运行配置变化。

DONE / PASS：python3 scripts/test-sandbox-exposure.py通过4项浏览器及真实PG/Kafka账本/投影与宿主最终对账；test-natural-exposure.py通过5项；test-feed-exposure.py通过5项及双owner最终对账。合计14浏览器项全部实际执行通过，三个宿主均正常退出。git diff --check通过；七项保护文件指纹未变。修复仅测试装配，沿用现有构建和专用验收，无新增无关测试。日志/tmp/recsys-s11-browser-{wiring,natural,feed}.log。未将CPA尚未交付的工作树改动或七项用户保护文件纳入本修复。

SKILL_HANDOFF: implementation-validation → update-progress-docs → task-git-delivery；ER-S11C2-R1 DONE，validation=PASS，待正常本地提交/合并/push main及远程CI核对。生产部署未执行。

Git/CI事实：修复6904889c6e38784a7b61d7fb01da3ab8edef60bd已合并推送main；独立CI被后续main运行取消，继承修复的2f5c3dab之CI38699749896全部SUCCESS，远程浏览器门禁已关闭。
