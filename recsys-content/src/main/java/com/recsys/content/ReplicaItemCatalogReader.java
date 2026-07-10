package com.recsys.content;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 本地副本 item 目录读者:读 {@code item_local}(离线 {@code sync-item-catalog} 从 content 权威库灌入)。
 * {@code recsys.content.item-source=replica} 时激活 —— item 物理搬到 content 自有库后,rec-serving 逐候选热路径不再跨库直读。
 */
@Component
@ConditionalOnProperty(name = "recsys.content.item-source", havingValue = "replica")
public class ReplicaItemCatalogReader extends AbstractItemCatalogReader {

    public ReplicaItemCatalogReader(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    protected String itemTable() {
        return "item_local";
    }
}
