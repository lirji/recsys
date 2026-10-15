# 迁移期间的浏览器门禁修复

MP10b提交e0949431的CI38734120451：资金链、自然曝光及security成功；browser job的sandbox4/自然5通过，混排4通过/1失败。失败发生在feed-exposure.spec.ts的原生hidden场景：`Target.createTarget`返回后，`document.visibilityState`连续5秒仍为visible，尚未到达hidden期间不计费的断言。原日志 `/tmp/recsys-mp10b-ci-browser.log`，对应[job116418952425](https://github.com/lirji/recsys/actions/runs/38734120451/job/116418952425)。该运行不能记全绿。

三套原生浏览器测试具有相同的创建后直接等待hidden写法，现创建空白页签后显式调用`Target.activateTarget`激活返回的targetId。随后仍通过原生document.visibilityState确认hidden，再核对数据库无新增事实；回到原页仍调用同一个激活API。没有覆盖visibilityState、取消断言、增加测试重试或改变产品曝光计时逻辑。

本地已安装playwright-core/types/protocol.d.ts对activateTarget的说明是激活目标，创建和激活是不同命令；[官方Target协议](https://chromedevtools.github.io/devtools-protocol/tot/Target/)提供对应方法。本次修复针对日志观察到的创建完成后前台状态未切换，不能将单次失败推断为SQL回归。

## 验证

本地DONE/PASS。类型检查、三个文件Prettier和ESLint通过；混排5项、自然5项、sandbox4项真实浏览器全部通过，包含三个原生hidden场景及各自数据库最终验收。日志`/tmp/recsys-native-tab-{feed,natural,sandbox}.log`。三个测试文件指纹`81166c7147120d2b24f267a5e9219f3438b0ea9104a7153e2723ea2d4afc7683`；与已通过专项的MP11a迁移同一工作树验证，独立于MP11a产品迁移提交，后续推送HEAD仍需核验远程CI。

联合hygiene无阻塞（`/tmp/recsys-mp11a-native-hygiene.json`）；Java统一formatter/独立Java分析工具的既有限制保持，前端这三个测试文件已显式运行项目Prettier/ESLint。保护文件及历史SQL不变。
