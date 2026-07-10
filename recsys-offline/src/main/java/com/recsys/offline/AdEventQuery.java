package com.recsys.offline;

import org.springframework.boot.ApplicationArguments;

import java.util.Set;

/**
 * ad_event 读的来源表切换(#3 ad-serving 上下文物理拆库,离线侧)。镜像 {@link BehaviorQuery#table}/{@link ItemQuery#table}:
 * 默认 {@code ad_event}(直读 ad-serving 上下文,回滚落点);{@code ad_event_log} = 读数据平台<b>自有</b>读仓
 * (DB-per-service,由 {@code sync-ad-event-log} 从 ad-serving 库同步)。仅白名单值,表名直接进 SQL 串,防注入。
 *
 * <p>用法:分析作业在 {@code run} 起始取一次存作业级字段(单次运行无并发),供 SQL 引用。写作业
 * (sim-ad-events/seed-ads)不用本类,改由 {@code adDbJdbc}(AD_PG_DB)写 ad-serving 自有库。
 */
final class AdEventQuery {

    /** 允许的 ad_event 读表白名单(表名直接进 SQL 串,白名单防注入)。 */
    private static final Set<String> ALLOWED_TABLES = Set.of("ad_event", "ad_event_log");

    private AdEventQuery() {
    }

    /**
     * 解析 {@code --ad-event-table}:ad_event 读的来源表。默认 {@code ad_event};{@code ad_event_log} = 读自有读仓。
     * 仅白名单值,防表名注入。
     */
    static String table(ApplicationArguments args) {
        String t = args.containsOption("ad-event-table")
                ? args.getOptionValues("ad-event-table").get(0) : "ad_event";
        if (!ALLOWED_TABLES.contains(t)) {
            throw new IllegalArgumentException("非法 --ad-event-table(仅 ad_event / ad_event_log): " + t);
        }
        return t;
    }
}
