# MP13e3：评估、融合调参与推荐校准输入

DONE/PASS。8源文件指纹`d07d67c4127202e24fcfce406fc252ea37b0b151b7215ed7a08ab15ba342e0ad`，清单`/tmp/recsys-mp13e3-source-paths.json`。

EvalJob、RecCalibrateJob、TuneFusionJob的四处SQL合并为主源EvaluationInputMapper/XML两种读取。评分不连接目录、不提前过滤低分/重复记录；保留NULL数值读取0、PG epoch::bigint转秒、Java Math.round(value*10)后的正反馈阈值。目录热度下限0，与负采样下限1明确区分；重复身份按原顺序覆盖Map，但每行仍累计总热度。两个具名record用于输入，ResultHandler沿原列表/Map聚合，不新增Repository、事务或依赖。去掉三个作业可变SQL表名字段，单次来源限定枚举传递。

- 旧7真实PG/Redis两次通过，新评估7+实际四源装配1共8通过；完整离线212项/38类0失败/错误/跳过、全仓24 reactor测试编译、hygiene通过。日志`/tmp/recsys-mp13e3-{before,after,offline,compile}.log`。
- 召回/排序/内容服务替身只固定候选与分数，评分查询、CSV与Redis真实；文件只在子进程临时目录。保留历史排除、3.95/3.94评分舍入边界、NULL/Long.MAX_VALUE、微秒时间舍入、固定切分点102、全局时间分位、抽样seed与max-users、目录重复/NULL/负热度、不同K指标、融合网格与校准曲线。
- 主/读仓两来源完整输出相等；无测试正例/只有历史重复、样本不足保持旧校准或不产报表。来源非法/缺行为表/缺目录在发布前失败；单用户召回/排序失败继续其余用户的原策略不改。所有读取连接在子进程退出前检查关闭及服务端退出。
- 旧完整输出SHA256：评估`33d46a229c760e538f44871c9f949807d2eae26bf7de264032f08befff46b8ec`，调参`b867ae79b8e85db8657761dc628c5bba799e691e421fd61e858c136fae1f9db5`，校准`b07af253a5fc43cdd0114bdfdaacb58679f589306d870ba8a4710a8c45ff974e`；部分用户失败的评估`c3d24ad25ba59c1a98a495a80a2c2ac6e6e2f585489bf5a237a6c9f1d80337bc`，调参`a3a924cf5c766353091e4b5bdb075ea5401f98308beacf9b3c3e82842939ff1c`，校准`4caf4c21901d62158aaf808f62c84b3304bc6989b74062746f92d9614b11cee7`。迁移后均一致。
- 45历史SQL/8保护摘要不变。Java formatter与独立静态分析未配置限制保持，不替代MP15完整验收。

前批e2及两项CI测试修正已正常ff/push至12851b30；CI38768505808资金/自然曝光/真实浏览器/安全四门禁SUCCESS，build在途。资金午夜及HTTP429失败已由新资金门禁成功关闭；FTRL修正的远程完整build仍须核验。下一片GenAdUpliftSamplesJob的成熟随机分配/独立转化事实关联，先冻结窗口边界与整用户切分。
