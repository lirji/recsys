package com.recsys.content;

import com.recsys.common.content.ItemCatalogReader;
import com.recsys.common.query.TermWeight;
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
    public List<ScoredId> lexicalSearch(String query, List<TermWeight> terms, int limit) {
        List<TermWeight> boosted = terms == null ? List.of() : terms.stream()
                .filter(t -> t != null && t.term() != null && !t.term().isBlank())
                .filter(t -> Double.isFinite(t.weight()) && t.weight() > 1.0)
                .toList();
        if (boosted.isEmpty()) {
            return lexicalSearch(query, limit);
        }

        // R8-FTS:静态 tsvector 的 A/B/C/D 只能表示文档字段权重,不能表达“本次 query 的 IDF”。
        // 候选仍由组合 OR tsquery + GIN 过滤;在候选内逐词算 cover-density rank,
        // 以 (IDF-1) 加权后叠加到原始 rank。全部 IDF=1 时走旧 SQL,保持等权评分语义。
        String[] termArray = boosted.stream().map(TermWeight::term).toArray(String[]::new);
        Double[] boostArray = boosted.stream().map(t -> t.weight() - 1.0).toArray(Double[]::new);
        return jdbc.query(
                "WITH base_q AS (SELECT to_tsquery('english', " +
                "    NULLIF(replace(plainto_tsquery('english', ?)::text, '&', '|'), '')) AS tsq), " +
                "weighted_terms AS (" +
                "    SELECT term, boost, plainto_tsquery('english', term) AS tsq " +
                "    FROM unnest(?::text[], ?::float8[]) AS t(term, boost) " +
                "    WHERE boost > 0 AND numnode(plainto_tsquery('english', term)) > 0" +
                "), boost_total AS (" +
                "    SELECT SUM(boost) AS total FROM weighted_terms" +
                "), candidates AS (" +
                "    SELECT i.item_id, i.title_tsv, ts_rank_cd(i.title_tsv, b.tsq) AS base_score " +
                "    FROM " + itemTable() + " i CROSS JOIN base_q b WHERE i.title_tsv @@ b.tsq" +
                "), idf_score AS (" +
                "    SELECT c.item_id, SUM(w.boost * ts_rank_cd(c.title_tsv, w.tsq)) " +
                "        / NULLIF(t.total, 0) AS boost_score " +
                "    FROM candidates c JOIN weighted_terms w ON c.title_tsv @@ w.tsq " +
                "    CROSS JOIN boost_total t GROUP BY c.item_id, t.total" +
                ") SELECT c.item_id, c.base_score + COALESCE(s.boost_score, 0) AS score " +
                "FROM candidates c LEFT JOIN idf_score s ON s.item_id=c.item_id " +
                "ORDER BY score DESC, c.item_id ASC LIMIT ?",
                ps -> {
                    ps.setString(1, query);
                    ps.setArray(2, ps.getConnection().createArrayOf("text", termArray));
                    ps.setArray(3, ps.getConnection().createArrayOf("float8", boostArray));
                    ps.setInt(4, limit);
                },
                (rs, n) -> new ScoredId(rs.getLong("item_id"), rs.getDouble("score")));
    }

    @Override
    public List<ScoredId> lexicalSearch(String query, int limit) {
        return jdbc.query(
                "WITH q AS (SELECT to_tsquery('english', " +
                "    NULLIF(replace(plainto_tsquery('english', ?)::text, '&', '|'), '')) AS tsq) " +
                "SELECT item_id, ts_rank_cd(title_tsv, q.tsq) AS score " +
                "FROM " + itemTable() + ", q WHERE title_tsv @@ q.tsq " +
                "ORDER BY score DESC, item_id ASC LIMIT ?",
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
