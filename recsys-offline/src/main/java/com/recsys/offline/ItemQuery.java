package com.recsys.offline;

import org.springframework.boot.ApplicationArguments;

import java.util.Set;

/**
 * item 目录读的来源表切换(#3 content 上下文物理拆库,离线侧)。镜像 {@link BehaviorQuery#table}:
 * 默认 {@code item}(直读 content 上下文,回滚落点);{@code item_local} = 读 rec-serving/数据平台<b>自有</b>
 * 本地读模型(DB-per-service,由 {@code sync-item-catalog} 灌)。仅白名单值,表名直接进 SQL 串,防注入。
 *
 * <p>用法:作业在 {@code run} 起始取一次 {@code ItemQuery.table(args)} 存作业级字段(单次运行无并发),
 * 供直写 SQL 的 helper 引用。ContentService 路的作业(BackfillEmbedding/BanditStats/GenAdCvrSamples)
 * 不用本类,改由 {@code recsys.content.item-source=replica} 让 ContentService 读 item_local。
 */
final class ItemQuery {

    /** 允许的 item 读表白名单(表名直接进 SQL 串,白名单防注入)。 */
    private static final Set<String> ALLOWED_TABLES = Set.of("item", "item_local");

    private ItemQuery() {
    }

    /**
     * 解析 {@code --item-table}:item 读的来源表。默认 {@code item};{@code item_local} = 读本地读模型。
     * 仅白名单值,防表名注入。
     */
    static String table(ApplicationArguments args) {
        String t = args.containsOption("item-table")
                ? args.getOptionValues("item-table").get(0) : "item";
        if (!ALLOWED_TABLES.contains(t)) {
            throw new IllegalArgumentException("非法 --item-table(仅 item / item_local): " + t);
        }
        return t;
    }
}
