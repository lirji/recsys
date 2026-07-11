package com.recsys.recall.channel;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户近期正反馈物品的<b>单一来源</b>。
 *
 * <p>I2I / SWING / GENERATIVE 三路召回原先各自跑<b>完全相同</b>的一条 SQL
 * ({@code DISTINCT item_id ... action IN ('CLICK','LIKE','PLAY','RATING') ORDER BY item_id DESC LIMIT n})
 * 取种子物品——一次推荐里这条查询被打三遍,并在并行召回时各占一条连接。本类把它收敛为
 * {@link com.recsys.recall.MultiChannelRecallService} 每请求调用一次,结果经
 * {@link com.recsys.common.recall.RecallContext#recentPositiveItems()} 下发,三路取前缀消费。
 *
 * <p><b>零语义变化</b>:排序确定(item_id 降序)、动作集合一致,故"limit=max 的整表"取前 k 前缀
 * 与"limit=k 的查询"逐项相等。各路的 seed 数({@code i2iSeed}/{@code generativeSeed})都 ≤ 预取 limit。
 */
@Component
public class RecentPositiveItemsSource {

    private final JdbcTemplate jdbc;

    public RecentPositiveItemsSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 用户近期正反馈物品,item_id 降序,至多 {@code limit} 个。
     * 与 I2I/SWING/GENERATIVE 原 {@code recentItems} 的 SQL 逐字对齐(动作集、去重、排序、limit)。
     */
    public List<Long> recentPositiveItems(long userId, int limit) {
        return jdbc.queryForList(
                "SELECT DISTINCT item_id FROM user_behavior WHERE user_id=? " +
                "AND action IN ('CLICK','LIKE','PLAY','RATING') ORDER BY item_id DESC LIMIT ?",
                Long.class, userId, limit);
    }
}
