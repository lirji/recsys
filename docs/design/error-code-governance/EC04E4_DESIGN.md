# EC04e4 用户360部分读取未知

基线3434795d17ba031bab0da5e50fb038d863bad949；父EC04继续，基线复用E3的101项真实PG/HTTP/观测专项与157前端。只处理console.UserProfileService/View/Mapper职责注释和User360页面的部分读取错误；不改SQL/事务/认证/兴趣保存或推荐算法，不新增中间件。

## 契约与取舍

保留分区独立读取与成功数据，不将一项故障改成整个视图503。响应继续HTTP200和原字段，新增errors对象（受控section→目录code），前端将其视为可选以兼容旧成功响应。profile/embedding/actions/categories/buckets/recent/realtime使用明确稳定section编码；DB错误统一CONSOLE_USER_DATA_UNAVAILABLE，Redis错误CONSOLE_USER_REALTIME_UNAVAILABLE，均为对象内码。

失败的标量由0/false改为null：exists、hasEmbedding、stats.totalInteractions/distinctCategories、realtime.recCached/seenCount。Map/List维持结构并以errors标记不可读，不能把中途异常留下的部分actionCounts算作完整结果。exists只有profile或行为明确证明存在时true，两项都成功且均无记录才false，其余null；NULL画像行仍存在。单项类别失败不抹掉其他行为/画像。Mapper未装配明确标记六项DB读取未知；Redis未装配与不可读均available=false，相关数值null。

Redis损坏计数/成员/非预期null回复不再静默变0或跳过后显示完整；此分区标记不可读。合法浮点计数仍向零取整展示，用BigDecimal校验非负/有限及long范围，保留现有展示口径；有效缺键的空集合/false/0仍正常。只读、无新重试/写入；不宣称跨查询为一致数据库快照。

前端保留原布局，顶部汇总已知section的安全码/说明，失败字段显示“未知”，成功字段继续可见；真实不存在才显示无画像/行为。Redis unavailable不再显示0/无缓存/无偏好。近期行为读取失败不以表格空态表示没有行为。兴趣异步待完成显示加载中，仍使用E1已有错误码。响应errors中的任意文案/未知section不用于展示，当前section采用本地目录提示。新API错误null需要配套新前端；旧前端可能误读旧假值，故不宣称混合版本具备完整未知展示。

## 验证

真实PG保护已知用户/全NULL画像/真正缺失/时间/分页和部分查询故障，扩展profile/actions失败的存在性三态，HTTP保留字段并显示errors安全码。受控Mapper流式handler中途故障必须丢弃部分计数；Redis用获授权的独占容器验证正常缺键/值、损坏计数/序列、断连与恢复，不使用共享实例；必要纯解析边界单测明确范围。真实React组件覆盖部分成功、全部未知、确实缺失、旧成功响应、Redis不可读、未知错误文案不泄漏。全console/前端测试/build、全仓compile、目录/持久边界及hygiene。

回退无schema变更但会恢复旧0/false伪空；保留用户已有文件。Item和SystemOps/作业状态，以及EC05～08仍继续，本片不称父目标完成。
