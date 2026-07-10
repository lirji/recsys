package com.recsys.content;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 JdbcTemplate 的物品服务实现,默认读共享 {@code item} 表。
 *
 * <p>#3 content 拆库:{@code findByIds} 是排序逐候选类目特征(rank×10 + ad CVR)的热路径读。item 表名由
 * {@link #table()} 给,{@code recsys.content.item-source=replica} 时激活的子类 {@link ReplicaJdbcContentService}
 * 覆盖为读本地副本 {@code item_local}。owner(content-service)不设该开关 → 默认 {@code db} → 读权威 {@code item}。
 */
@Service
@ConditionalOnProperty(name = "recsys.content.item-source", havingValue = "db", matchIfMissing = true)
public class JdbcContentService implements ContentService {

    private final JdbcTemplate jdbc;

    public JdbcContentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** item 目录表名:{@code item}(共享,默认)或子类覆盖为 {@code item_local}(本地副本)。 */
    protected String table() {
        return "item";
    }

    private static final RowMapper<Item> MAPPER = (rs, n) -> {
        Array tagsArr = rs.getArray("tags");
        List<String> tags = new ArrayList<>();
        if (tagsArr != null) {
            String[] arr = (String[]) tagsArr.getArray();
            for (String t : arr) {
                tags.add(t);
            }
        }
        return new Item(
                rs.getLong("item_id"),
                rs.getString("title"),
                rs.getString("category"),
                tags,
                rs.getString("description"),
                rs.getDouble("popularity"));
    };

    @Override
    public Item findById(long itemId) {
        List<Item> list = jdbc.query(
                "SELECT item_id,title,category,tags,description,popularity FROM " + table() + " WHERE item_id=?",
                MAPPER, itemId);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public Map<Long, Item> findByIds(List<Long> itemIds) {
        Map<Long, Item> result = new HashMap<>();
        if (itemIds == null || itemIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", itemIds.stream().map(x -> "?").toList());
        List<Item> items = jdbc.query(
                "SELECT item_id,title,category,tags,description,popularity FROM " + table()
                        + " WHERE item_id IN (" + placeholders + ")",
                MAPPER, itemIds.toArray());
        for (Item it : items) {
            result.put(it.itemId(), it);
        }
        return result;
    }

    @Override
    public List<Long> allItemIds() {
        return jdbc.queryForList("SELECT item_id FROM " + table() + " ORDER BY item_id", Long.class);
    }

    @Override
    public void save(Item item) {
        // tags 作为 text[] 写入
        String[] tagsArr = item.tags() == null ? new String[0] : item.tags().toArray(new String[0]);
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO " + table() + "(item_id,title,category,tags,description,popularity) " +
                    "VALUES(?,?,?,?,?,?) " +
                    "ON CONFLICT(item_id) DO UPDATE SET " +
                    "title=EXCLUDED.title,category=EXCLUDED.category,tags=EXCLUDED.tags," +
                    "description=EXCLUDED.description,popularity=EXCLUDED.popularity");
            ps.setLong(1, item.itemId());
            ps.setString(2, item.title());
            ps.setString(3, item.category());
            ps.setArray(4, con.createArrayOf("text", tagsArr));
            ps.setString(5, item.description());
            ps.setDouble(6, item.popularity());
            return ps;
        });
    }

    @Override
    public long count() {
        Long c = jdbc.queryForObject("SELECT count(*) FROM " + table(), Long.class);
        return c == null ? 0 : c;
    }
}
