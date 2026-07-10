package com.recsys.recengine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 搜索个性化打分:用离线聚合的 BGE {@code user_embedding}(长期口味)对候选物品算余弦亲和度。
 *
 * <p>用途——搜索场景的"温和个性化":在 query 相关性主导的前提下,把同样相关的结果按用户口味
 * 微调次序(见 {@code RecommendOrchestrator} 搜索融合)。相比 {@code app_user.profile} 类目
 * (MovieLens 用户基本没填),user_embedding 对真实用户已全量覆盖(610/610),信号更可用。
 *
 * <p>一次 pgvector 查询拿到候选集对用户向量的余弦:用户无向量(冷用户)→ 子查询为 null →
 * 返回空表(无个性化,纯相关性),优雅降级。失败开放:异常返回空表,不拖垮搜索主链路。
 */
@Component
public class PersonalizationScorer {

    private static final Logger log = LoggerFactory.getLogger(PersonalizationScorer.class);

    private final JdbcTemplate jdbc;

    public PersonalizationScorer(@org.springframework.beans.factory.annotation.Qualifier("derivedJdbc")
                                 JdbcTemplate jdbc) {   // #3:item_embedding+user_embedding(一条 SQL)走派生库
        this.jdbc = jdbc;
    }

    /**
     * 候选物品 → 与用户向量的余弦亲和度(∈[-1,1],一般取 max(0,·) 用)。
     * 用户无 user_embedding 或候选为空 → 返回空表。
     */
    public Map<Long, Double> affinity(long userId, Collection<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Double> out = new HashMap<>(itemIds.size() * 2);
        try {
            Long[] ids = itemIds.toArray(new Long[0]);
            jdbc.query(con -> {
                var ps = con.prepareStatement(
                        "SELECT e.item_id, " +
                        "       1 - (e.embedding <=> (SELECT embedding FROM user_embedding WHERE user_id=?)) AS aff " +
                        "FROM item_embedding e WHERE e.item_id = ANY(?)");
                ps.setLong(1, userId);
                ps.setArray(2, con.createArrayOf("bigint", ids));
                return ps;
            }, rs -> {
                double aff = rs.getDouble("aff");
                if (!rs.wasNull()) {
                    out.put(rs.getLong("item_id"), aff);
                }
            });
        } catch (Exception e) {
            log.debug("个性化亲和度计算失败 user={}(降级无个性化): {}", userId, e.getMessage());
            return Map.of();
        }
        return out;
    }
}
