# MP13e5：广告延迟转化训练样本

DONE/PASS。10源文件指纹`45c17b8f873f657aefb568cbd0dd1753bf21252a87aa1da71d32fa474ebe7842`，清单`/tmp/recsys-mp13e5-source-paths.json`。

GenAdCvrSamplesJob三SQL分别迁入主源AdCvrInputMapper与逻辑分片AdItemMapper/XML。新增AdEventSource限定两个原来源，AdEventQuery保留旧白名单入口给尚未迁移作业；本作业不再存可变表名。点击改为具名Click记录，转化和广告关联继续回调聚合，不添加中间全量列表、事务、池或依赖。广告关联确实覆盖两个物理分片，不能改成直接读主库ad。

- 旧6项真实双PG/分片测试通过，再运行2项固定完整CSV；新6+四源装配1通过。完整离线224项/40类0失败/错误/跳过、全仓24 reactor测试编译通过。日志`/tmp/recsys-mp13e5-{before,hashes,after,offline,compile}.log`。
- 默认完整CSV SHA256=`8ce52d982ed82e235461988041eafb9c16bbca5b24e900813fae4c4c74101765`；days非正/seed7完整CSV=`b5ab8ca91dac00a4f3f09fdd6e0ca608ccd2e496c3ecccbfde77d7464ebf98ff`。迁移后及读仓来源均一致，固定输出路径仅在子进程临时目录，特征/内容服务固定替身不宣称验证其生产实现。
- 精确时间边界通过原主源外层事务冻结PG now验证；普通autocommit另测，未修改生产时钟/事务。覆盖30天含端点/微秒外界、未来点击、已到达转化、重复转化取最早、缺广告/物品跳过、NULL身份/时间/请求及Long.MAX_VALUE、默认和指定seed、缺类目与五特征输出。
- 原匹配不要求转化晚于点击，负延迟仍可能出现；NULL request与字符串null共享Java拼接键、NULL时间全量读取为0均保留，不能把修订标签策略混入SQL迁移。无点击保留旧文件；所有缺关联只写表头；SQL/非法来源在输出前失败，晚期用户特征失败保留前三行旧部分输出边界。
- 子进程退出前检查主源真实连接关闭和两个真实分片池active=0；随后正常关闭逻辑源。45历史SQL/8保护摘要不变；Java formatter/独立静态分析缺失限制保持，不替代MP15。

前批e3=e0a78f1c/e4=da335f70均已ff/push。da335f70的CI38770885163资金/自然/浏览器/安全SUCCESS、build尚在途，本片先本地提交，继续广告校准、探索和延迟聚合后相邻交付。
