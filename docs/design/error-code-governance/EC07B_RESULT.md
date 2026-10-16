# EC07b 剩余错误展示与实验写入验收

状态：DONE/PASS（本地），与EC07c按同一错误展示闭环交付。基线a434bafa；全任务EC08门禁仍在进行，不能据此宣称整体完成。

原异常、堆栈、OIDC未知错误和实验reason不再进入页面；复制/空搜索/租户/本地交付错误进入目录，取消不弹错误toast。实验后端保留HTTP200/ok:false/reason，增加code并使用固定安全文案。前端只有明确ok:true才显示成功；坏ACK/失败后重查实际配置，逐变体部分成功停止后续写入。目录新增15项为256，原241和8码族保持。

新增测试发现快照刚到、编辑态尚未同步时原保存可能提交0/0；现以真实快照权重作显示与请求共同默认，严格50/50断言保持。页面布局和原权限/身份/回执恢复、负权重归零、Redis字段/成功协议均保持。

验证：改前后端1/前端182基线通过；最终实验真实Redis/HTTP方法授权6项、全前端202项/31文件、最终认证13项、build和24模块compile通过。Redis ACL拒绝hset/del及恢复、写入已完成但invalidate失败保留真实效果、所有写路由非ADMIN403均有断言。首次MockMvc测试缺JSON Accept、AntD按钮两字间距及旧租户文案断言已修；未放宽业务断言。最后一次仅测试显式AxiosAdapter类型/void整理，13专项及build重新通过，产品源码未再修改。

浏览器/取消/OIDC及源码守卫由EC07c共同验证，见EC07C_RESULT。前端Prettier/ESLint通过，4条既有warning无error；Python错误目录10项、生成、公开出口守卫与持久守卫606Java90XML45SQL通过。手工去格式差异审查699行已完成，无阻断；无新依赖/SQL/schema/事务或写重试。Java统一formatter及独立静态分析未配置，本轮原hygiene runner不可发现，因此/tmp/recsys-ec07bc-hygiene.json明确记录手工审查及实际检查，不冒充自动工具运行。

共同43源码/测试/CI源指纹`a5faabcfc8af49487446c499faf081ebf7df79a5be80dd69bd0901f815cda849`（排序路径NUL内容NUL）；清单/tmp/recsys-ec07bc-source-paths.json。验证后源修改会使本结果过期。

- `/tmp/recsys-ec07b-backend-final.log`：`cfc0cb0a1598990b1776faa7f7e7eac453cae60ad0829b6cd53f1df2fad4b186`
- `/tmp/recsys-ec07b-compile.log`：`7c36bdbbdd0cc2199a8f12ac1c441bf461a1e09e2c091297cafc060e07051439`
- `/tmp/recsys-ec07bc-frontend.log`：`59e13d1417559163c4bf4d5febbc0d50b9633197a072a28ae01bd4c8c67e36f2`
- `/tmp/recsys-ec07c-auth-final.log`：`3bebaa7c64342b75d6bdf5d7f7d56cde29c763eeff70c2396494cc55c8063344`
- `/tmp/recsys-ec07bc-build-final.log`：`f72a1b3882dc05a0872dba96ba282e8006d7c7a80b593579c46f9b25a1958664`
- `/tmp/recsys-ec07bc-browser-verified.log`：`a869d4da91e4962507a0202125264ac34b6aa9cdee7075a32a27719205e375bd`

8保护文件及暂停交接保持，不清理用户文件、不部署。前基线CI38822642306五门SUCCESS，新交付SHA另核验。
