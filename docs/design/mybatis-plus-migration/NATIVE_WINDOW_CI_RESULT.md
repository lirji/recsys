# 原生可见性CI夹具：明确同窗切换

MP12c CI38739717158的资金链/自然/security通过，浏览器门禁中sandbox4、自然5通过，混排4通过/1失败。失败仍为切换后document.visibilityState保持visible（`/tmp/recsys-mp12c-ci-browser.log`）；34a2a7a7的显式激活不足以消除该故障，不能把历史失败改记成功。

依据Chromium [Target实现](https://raw.githubusercontent.com/chromium/chromium/main/chrome/browser/devtools/protocol/target_handler.cc)，createTarget未指定窗口时会按当前激活窗口寻找或创建窗口。旧夹具同时传启动URL和在CDP端创建target，可能出现启动窗口与测试窗口竞争；这是基于代码与失败的原因推断，CI旧日志没有窗口ID，尚不能声称该次运行已直接证明窗口不同。

夹具改为`--no-startup-window`，再显式创建并激活唯一测试窗口；后台动作明确newWindow=false，比较两个target的windowId后才激活另一真实页签。原hidden/visible断言、重新连续计时和PG零事实/唯一账本断言不变，不覆写visibilityState，不增加测试重试或超时。窗口选择规则可参照[CDP Target定义](https://chromedevtools.github.io/devtools-protocol/tot/Target/)，禁启动窗口开关见[Chromium定义](https://github.com/chromium/chromium/blob/main/chrome/common/chrome_switches.h)。

4源文件指纹`cb0abddb6eb468b7b58c69056cfc8e7ef87578ea1b072c232f05d32d4e69c2f1`。本地12次独立原生Chromium生命周期探针全部通过同窗hidden/visible切换，含1280×450设备尺寸设置（`/tmp/recsys-native-window-probe-fixed.log`）；旧12次本地探针也未复现CI失败，故不以本地结果声称已复现远程故障。TypeScript、Prettier与ESLint通过；三套真实浏览器随后串行验证。

三套真实浏览器全部通过：混排5、自然5、sandbox4（`/tmp/recsys-native-window-{feed,natural,sandbox}.log`）。联合hygiene无阻塞；产品MP12d的资金链382/自然75及源码指纹均未改变。仅本地DONE/PASS，修复后远程CI尚未运行，不宣称远程故障已关闭。


修复后远程验收已完成：[CI 38741661134](https://github.com/lirji/recsys/actions/runs/38741661134) 对应541651d7，五个门禁（build、money-chain、natural、三套browser、security）全部SUCCESS，build于2026-10-15T13:29:03Z完成；console-ci38741660789也全部SUCCESS。原生可见性夹具的当前远程故障已关闭，历史失败及其原因推断的证据限制保留。
