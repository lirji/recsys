# MP13f3：多触点归因与广告质量分输入

DONE/PASS。9源文件指纹`50c09a07f05846541bca8ae6abe9c0d67bc291b50e9b7a8ec89257912203c388`，清单`/tmp/recsys-mp13f3-source-paths.json`。

AttributionJob的一条有序事件查询、QualityScoreJob的三个分组聚合进入主源AdAttributionMapper/AdQualityMapper与XML。AdEventSource局部传参取代可变aet字段，days值绑定，读取继续ResultHandler聚合，不改路径/分权、质量归一/先验算法，不新增Repository、事务、池或依赖。归因AttributionKind显式对应数据库字符串code与原0/1/2路径码，不使用ordinal，消除迁移行的闭集字符串比较。

- 旧11真实PG/Redis及子进程CSV专项、新11+四源装配1通过。实际事件时间列TIMESTAMP，原主源外层事务仅在测试冻结PG now；文件只在子进程临时目录，退出前检查真实连接关闭及服务端退出。
- 归因严格按原始user_id与原始时间表达式排序，投影NULL→0不能提前改变NULLS LAST。验证NULL与实际0用户合并后的原顺序、NULL转化时间读0、CTA/VTA端点、点击优先/同类型最近、未来事件、无触点回退、三模型和未知模型回退、非正days全量及读仓来源。
- 原归因double[]路径将9007199254740993舍入为9007199254740992，已明确测试和注释，本次只保持SQL兼容，不宣称已修复。后续类型治理应用long身份的具名路径记录并单独验证输出变化。Quality原long身份无此损失，测试明确区分两者。
- 质量保留曝光COUNT(*)/相关性NULL为0、点击/转化COUNT(DISTINCT request_id)排除NULL请求、按原广告身份分组、缺曝光忽略、未来事件及days端点、默认20曝光阈值/低量旧键、自定义先验权重clamp、非正权重跳过。
- 真实Redis ACL在第二广告写入拒绝时保留首广告和首CSV行、第二广告旧值，原捕获异常正常返回的行为不改。两作业输出路径不可写时仍沿原捕获/完成日志；SQL或非法来源在创建报表前失败，空输入/无归因转化不产报表。后续统一错误提示须修正部分失败却报告完成的歧义。
- 五份完整旧CSV SHA256：质量默认`a2c6d011f044c1ef53901e08f632b2439bbb1cb640808f3aecb35dfd5d787c0a`；质量自定义`20ee560152d0cdd4687bb6b0170b24aaa5003106f46db8c20dcada89803efd2d`；归因position `1e895bb78b2f147601867c3e314f7759fc37d52ad130420c26001787d1242393`；linear `77358b1c133ca2dc809823c37c77d0436974cbf986ac338174b26d9b3f6d5c7a`；time-decay `c45c9befbdd3afcf04de7db34e3e69d3066a6a65c80e805e709e45b23bf7d15a`。新路径全部一致。

- 枚举实现完整离线252项/43类0失败/错误/跳过、全仓24 reactor测试编译通过（当时9源指纹756127656be129095ad9947ed0fab7bc15a91e4b3c25acb6dbc04ba158350beb）。随后仅将私有枚举由泛名EventType改为AttributionKind并完善注释，以明确它与计费Outbox表族的职责差别；计算/解析代码不变。最终受影响11+装配1共12、全仓24 reactor测试编译及hygiene再次通过，最终9源指纹以本文首段为准；不把更名前的全量指纹当作更名后证据。
- 首次HYG-MAGIC-STATUS要求闭集类型，随后HYG-DUPLICATE-ENUM提示泛名与BillingOutboxRepository.EventFamily重叠。独立归因类型使用精确名称，保留现有模块依赖，未引入计费仓储耦合或压制检查。最终门禁IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻塞；Java formatter及独立静态分析未配置限制保持。
- 日志`/tmp/recsys-mp13f3-{before-probe,after,final-offline,final-compile,final-naming,final-naming-compile}.log`；最终门禁`/tmp/recsys-mp13f3-final-naming-hygiene.json`。45历史SQL/8用户保护摘要不变，不替代MP15完整验收。

47ae6e02的CI38772834762五门禁全部SUCCESS。f2=2970a636与本片相邻正常ff合并/push后继续MP13f4 AdReport/AbReport六查询；必须保护原position NULL排序、曝光与关联事件的不同窗口、晚bucket失败部分输出及CUPED严格pre/post/ITT语义。
