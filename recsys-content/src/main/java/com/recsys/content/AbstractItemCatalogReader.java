package com.recsys.content;

import com.recsys.common.content.ItemCatalogReader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@link ItemCatalogReader} 的 JDBC 实现基类:所有 SQL 写一次,{@code FROM <itemTable()>} 由子类给表名。
 * {@code DbItemCatalogReader}(表={@code item},默认)/ {@code ReplicaItemCatalogReader}(表={@code item_local})。
 *
 * <p>SQL 逐字搬自原读者(LexicalRecaller/TagRecaller/ColdStartRecaller/HotRecaller/SemanticRecaller/
 * SimRankService/QueryUnderstandingServiceImpl),仅把 item 表名参数化 —— 故两来源产出 golden-diff 无损。
 * 各方法<b>不吞异常</b>(直接抛),优雅降级由调用方保留其原有 try/catch 语义。
 */
public abstract class AbstractItemCatalogReader implements ItemCatalogReader {

    protected final JdbcTemplate jdbc;

    protected AbstractItemCatalogReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** item 目录表名:{@code item}(共享)或 {@code item_local}(本地副本)。 */
    protected abstract String itemTable();

    @Override
    public List<ScoredId> lexicalSearch(String query, int limit) {
        return jdbc.query(
                "WITH q AS (SELECT to_tsquery('english', " +
                "    NULLIF(replace(plainto_tsquery('english', ?)::text, '&', '|'), '')) AS tsq) " +
                "SELECT item_id, ts_rank_cd(title_tsv, q.tsq) AS score " +
                "FROM " + itemTable() + ", q WHERE title_tsv @@ q.tsq ORDER BY score DESC LIMIT ?",
                ps -> {
                    ps.setString(1, query);
                    ps.setInt(2, limit);
                },
                (rs, n) -> new ScoredId(rs.getLong("item_id"), rs.getDouble("score")));
    }

    @Override
    public List<CatItem> byCategories(Collection<String> categories, int limit) {
        List<String> cats = new ArrayList<>(categories);
        String placeholders = String.join(",", cats.stream().map(c -> "?").toList());
        Object[] params = new Object[cats.size() + 1];
        for (int i = 0; i < cats.size(); i++) {
            params[i] = cats.get(i);
        }
        params[cats.size()] = limit;
        return jdbc.query(
                "SELECT item_id, category, popularity FROM " + itemTable() +
                " WHERE category IN (" + placeholders + ") ORDER BY popularity DESC LIMIT ?",
                (rs, n) -> new CatItem(rs.getLong("item_id"), rs.getString("category"), rs.getDouble("popularity")),
                params);
    }

    @Override
    public List<ColdItem> coldStartByCategory(int perCategory, int limit) {
        return jdbc.query(
                "SELECT item_id, popularity, category, rn FROM (" +
                "  SELECT item_id, popularity, category, " +
                "         ROW_NUMBER() OVER (PARTITION BY category ORDER BY popularity DESC) rn " +
                "  FROM " + itemTable() + " WHERE category IS NOT NULL" +
                ") t WHERE rn <= ? ORDER BY rn ASC, popularity DESC LIMIT ?",
                (rs, n) -> new ColdItem(rs.getLong("item_id"), rs.getString("category"),
                        rs.getDouble("popularity"), rs.getInt("rn")),
                perCategory, limit);
    }

    @Override
    public List<ScoredId> hotByPopularity(int limit) {
        return jdbc.query(
                "SELECT item_id, popularity FROM " + itemTable() + " ORDER BY popularity DESC LIMIT ?",
                (rs, n) -> new ScoredId(rs.getLong("item_id"), rs.getDouble("popularity")),
                limit);
    }

    @Override
    public List<String> recentTitles(long userId, int limit) {
        return jdbc.queryForList(
                "SELECT i.title FROM user_behavior b JOIN " + itemTable() + " i ON i.item_id=b.item_id " +
                "WHERE b.user_id=? AND b.action IN ('CLICK','LIKE','PLAY','RATING') " +
                "AND i.title IS NOT NULL ORDER BY b.ts DESC LIMIT ?",
                String.class, userId, limit);
    }

    @Override
    public List<CatId> recentRatedCategories(long userId, int limit) {
        return jdbc.query(
                "SELECT b.item_id AS item_id, i.category AS category FROM user_behavior b " +
                "JOIN " + itemTable() + " i ON i.item_id = b.item_id " +
                "WHERE b.user_id=? AND b.action='RATING' AND b.value>=4 ORDER BY b.ts DESC LIMIT ?",
                (rs, n) -> new CatId(rs.getLong("item_id"), rs.getString("category")),
                userId, limit);
    }

    @Override
    public List<CatCount> categoryCountsByTitleLike(List<String> ilikePatterns, int limit) {
        String[] patterns = ilikePatterns.toArray(new String[0]);
        return jdbc.query(
                "SELECT category, count(*) AS c FROM " + itemTable() + " " +
                "WHERE title ILIKE ANY (?) AND category IS NOT NULL " +
                "GROUP BY category ORDER BY c DESC LIMIT " + limit,
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", patterns)),
                (rs, n) -> new CatCount(rs.getString("category"), rs.getLong("c")));
    }

    @Override
    public List<String> distinctCategories() {
        return jdbc.queryForList(
                "SELECT DISTINCT category FROM " + itemTable() + " WHERE category IS NOT NULL", String.class);
    }
}
