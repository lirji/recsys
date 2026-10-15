package com.recsys.offline;

import org.springframework.boot.ApplicationArguments;

import com.recsys.content.persistence.model.CatalogTable;

/**
 * 离线目录来源参数：item为权威目录，item_local为离线自有读模型。
 * Mapper通过source取得内容模块既有封闭枚举；table仅供尚未迁移作业过渡使用，绝不返回任意输入。
 * ContentService路径继续由recsys.content.item-source配置选择来源，不与作业参数混用。
 */
final class ItemQuery {

    private ItemQuery() {
    }

    /**
     * 解析 {@code --item-table}:item 读的来源表。默认 {@code item};{@code item_local} = 读本地读模型。
     * 仅白名单值,防表名注入。
     */
    static String table(ApplicationArguments args) {
        return source(args) == CatalogTable.REPLICA ? "item_local" : "item";
    }

    /** Mapper只接收封闭来源；复用内容模块既有枚举，未迁移作业暂保留白名单表名入口。 */
    static CatalogTable source(ApplicationArguments args) {
        String value = args.containsOption("item-table") ? args.getOptionValues("item-table").get(0) : "item";
        return switch (value) {
            case "item" -> CatalogTable.OWNED;
            case "item_local" -> CatalogTable.REPLICA;
            default -> throw new IllegalArgumentException("非法 --item-table(仅 item / item_local): " + value);
        };
    }
}
