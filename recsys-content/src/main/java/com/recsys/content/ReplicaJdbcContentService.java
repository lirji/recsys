package com.recsys.content;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 本地副本物品服务:{@code findByIds}/{@code findById} 等读本地读模型 {@code item_local}(而非共享 {@code item})。
 * {@code recsys.content.item-source=replica} 时激活(默认 {@code db} → {@link JdbcContentService} 读 {@code item})——
 * item 物理搬到 content 自有库后,rec-serving 排序逐候选类目特征不再跨库直读。仅覆盖表名,SQL 全部继承。
 */
@Service
@ConditionalOnProperty(name = "recsys.content.item-source", havingValue = "replica")
public class ReplicaJdbcContentService extends JdbcContentService {

    public ReplicaJdbcContentService(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    protected String table() {
        return "item_local";
    }
}
