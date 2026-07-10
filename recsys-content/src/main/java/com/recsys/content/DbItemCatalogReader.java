package com.recsys.content;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 默认 item 目录读者:直读共享 {@code item} 表(#3 拆库前的行为,回滚落点)。
 * {@code recsys.content.item-source} 未设或 {@code db} 时激活。
 */
@Component
@ConditionalOnProperty(name = "recsys.content.item-source", havingValue = "db", matchIfMissing = true)
public class DbItemCatalogReader extends AbstractItemCatalogReader {

    public DbItemCatalogReader(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    protected String itemTable() {
        return "item";
    }
}
