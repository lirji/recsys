package com.recsys.offline;

import org.springframework.boot.ApplicationArguments;

import com.recsys.offline.persistence.model.AdEventSource;

/**
 * ad_event 读的来源表切换(#3 ad-serving 上下文物理拆库,离线侧)。镜像 {@link BehaviorQuery#table}/{@link ItemQuery#table}:
 * 默认 {@code ad_event}(直读 ad-serving 上下文,回滚落点);{@code ad_event_log} = 读数据平台<b>自有</b>读仓
 * (DB-per-service,由 {@code sync-ad-event-log} 从 ad-serving 库同步)。仅允许两个既有来源；新Mapper通过枚举选择固定XML分支。
 *
 * <p>用法:新分析作业在 {@code run} 起始解析来源枚举，以局部变量传递给Mapper。写作业
 * (sim-ad-events/seed-ads)不用本类,改由 {@code adDbJdbc}(AD_PG_DB)写 ad-serving 自有库。
 */
final class AdEventQuery {

    private AdEventQuery() {
    }

    /**
     * 解析 {@code --ad-event-table}:ad_event 读的来源表。默认 {@code ad_event};{@code ad_event_log} = 读自有读仓。
     * 仅白名单值,防表名注入。
     */
    static String table(ApplicationArguments args) {
        return source(args).code();
    }

    /** 单次执行只解析一次来源，避免可变表名字段跨调用残留。 */
    static AdEventSource source(ApplicationArguments args) {
        String t = args.containsOption("ad-event-table")
                ? args.getOptionValues("ad-event-table").get(0) : "ad_event";
        return AdEventSource.fromCode(t);
    }
}
