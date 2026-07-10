package com.recsys.recengine.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 默认已看来源:直读行为上下文的 {@code user_behavior} 表(与 P4 之前完全等价,回滚落点)。
 * {@code recsys.behavior.seen-source} 缺省或 = {@code db} 时生效。
 */
@Component
@ConditionalOnProperty(name = "recsys.behavior.seen-source", havingValue = "db", matchIfMissing = true)
public class DbSeenItemsSource implements SeenItemsSource {

    private static final Logger log = LoggerFactory.getLogger(DbSeenItemsSource.class);

    private final JdbcTemplate jdbc;

    public DbSeenItemsSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<Long> seenItems(long userId) {
        try {
            List<Long> ids = jdbc.queryForList(
                    "SELECT DISTINCT item_id FROM user_behavior WHERE user_id=? " +
                    "AND action IN ('CLICK','LIKE','PLAY','RATING')", Long.class, userId);
            return new HashSet<>(ids);
        } catch (Exception e) {
            log.warn("已看查询失败(user_behavior),本次不过滤 user={}: {}", userId, e.getMessage());
            return Set.of();
        }
    }
}
