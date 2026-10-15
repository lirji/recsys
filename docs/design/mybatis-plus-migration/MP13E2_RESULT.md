# MP13e2：自然推荐三类训练样本

DONE/PASS（本地）。8源文件指纹`dcad79a688e772d32e1c837f1c579784b8535401fe68a42ef707e4e358c1a26d`，清单`/tmp/recsys-mp13e2-source-paths.json`。

GenSamplesJob、GenSamplesMtJob、GenSamplesImprJob十处SQL集中为主源TrainingSampleMapper/XML五个查询：评分左连接、目录类目、加权物品池、曝光、四类归因反馈。五个具名record显式保留旧getLong/getDouble(NULL)=0、PG epoch::bigint四舍五入及位次优先级。评分/曝光仍加载原List，目录/物品池继续ResultHandler，不增全量中间List。移除三个Job可变bt/it字段，来源限定枚举经局部参数传递，不新加Repository/依赖/事务或生产DDL。

- 旧6真实PG测试通过后冻结六份完整CSV，再补空负池及同秒语义后旧7通过；新样本7+实际四源装配1共8通过。完整离线204项/37类0失败/错误/跳过、全仓24 reactor测试编译和hygiene通过。日志`/tmp/recsys-mp13e2-{before,after,offline,compile}.log`。
- CSV只写子进程临时目录；数据库是独占Testcontainers。验证默认as-of/全量特征对照/扩展8维、固定seed、热度及同类目负采样、时间切分、截断行为序列、位置代理与曝光位次、NULL/Long.MAX_VALUE/缺目录、主/读仓全部输出。
- 曝光同秒评分不参与快照或标签，窗口默认24小时/无限模式、LIKE同样赋click标签的旧语义保持；已纠正注释。空输入/无CTR正例保持旧文件；SQL失败发生在打开文件前；空负池失败仍留下表头与首条正例（原部分输出行为）。
- 子进程先检查真实Connection.isClosed再观察PG退出；首次测试夹具误让observer与reader同ApplicationName而计入自己，修正独立observer后通过；生产代码无此问题。
- 旧完整CSV SHA256：CTR默认`b62655806fdc95678949abc6f5aadc470259cfd7652ca2fd961d7dc365f5db34`、leaky扩维`14c70326b3a07ff07b1a62b57eec9b4c866593ec510bdbee3ff3a99162c246ac`；MT默认`28e7b017d4192eb5d11e5c293cd9f63ce828662065e95ffed77e9a589a4896da`、扩展选项`b8ba1b805473b8bb6b833f609dcfcd3b1d04624cb79a528ce8c50df67aa44fd7`；曝光默认`7a7674a7c3ea7f57619043aba339bd9d6c0f831d7e02a6025be7b94a0a5552e9`、无限窗口`211af398ceaf03e0d4f693b19d5c225170d5484a2d62d29156c1a2abcdc18008`。迁移后逐字节摘要一致。
- 45历史SQL/8保护摘要不变；formatter及独立静态分析未配置限制保持，不替代MP15完整验收。

前批d5/e1已ff/push到6a05f2ec，新CI38764359355资金门禁失败：ConversionOnlinePostgresTest跨广告主请求预期403实际400（remote参数）；与本片SQL范围无直接修改关系但尚未定因，须定位和关闭，不能宣称远程全绿。另三专项SUCCESS，build在途。本片先本地独立提交，完成该失败诊断后继续正常交付。

下一片MP13e3为Eval/RecCalibrate/TuneFusion重复评分输入及Eval目录查询；已准备临时目录/真实PG/Redis旧结果测试草稿，尚未改这三个产品类。
