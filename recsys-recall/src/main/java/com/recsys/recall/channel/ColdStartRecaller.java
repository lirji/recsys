package com.recsys.recall.channel;

import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷启动类目探索召回:按 {@code item.category} 分组,每类目取热度 Top-k,跨类目铺开。
 *
 * <p>用于新用户(无历史)的兴趣探索——纯热门会全是同一两个大类目,无法试探用户偏好;
 * 本路用窗口函数在每个类目内取前 k 名。默认按 rank 升序交错(各类目 rank1、再 rank2…)最大化覆盖。
 *
 * <p><b>contextual bandit(UCB)升级</b>:启用 {@link ColdStartBandit} 后,类目按 UCB 分排序 ——
 * 历史正反馈率高(exploit)+ 欠曝光(explore)的类目优先浮现,而非无差别铺开;类目内仍按热度名次
 * (rn)细分。命中后行为回流 → 离线 {@code cold-bandit-stats} 更新类目统计,越试越准。
 * bandit 关闭 / 无统计 → 退回 rn 交错(旧行为)。下游冷启动强多样性重排仍保证类目覆盖。
 *
 * <p>该路默认仅由编排在冷启动判定命中时启用(见 enabledChannels),普通请求不会触发。
 */
@Component
public class ColdStartRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(ColdStartRecaller.class);

    private final JdbcTemplate jdbc;
    private final RecallProperties props;
    private final ColdStartBandit bandit;

    public ColdStartRecaller(JdbcTemplate jdbc, RecallProperties props, ColdStartBandit bandit) {
        this.jdbc = jdbc;
        this.props = props;
        this.bandit = bandit;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.COLD;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        int perCat = props.getQuota().getColdPerCategory();
        int limit = props.getQuota().getCold();
        boolean useBandit = bandit.isEnabled();
        try {
            // 每个类目内按热度取前 perCat 名(rn),整体按 (rn, 热度) 交错;各行带 category/rn 供 bandit 打分
            List<Row> rows = jdbc.query(
                    "SELECT item_id, popularity, category, rn FROM (" +
                    "  SELECT item_id, popularity, category, " +
                    "         ROW_NUMBER() OVER (PARTITION BY category ORDER BY popularity DESC) rn " +
                    "  FROM item WHERE category IS NOT NULL" +
                    ") t WHERE rn <= ? ORDER BY rn ASC, popularity DESC LIMIT ?",
                    (rs, n) -> new Row(rs.getLong("item_id"), rs.getDouble("popularity"),
                            rs.getString("category"), rs.getInt("rn")),
                    perCat, limit);
            if (rows.isEmpty()) {
                return List.of();
            }
            // 每个「不同类目」只算一次 UCB 分(避免逐行打 Redis)
            Map<String, Double> catUcb = new HashMap<>();
            if (useBandit) {
                for (Row r : rows) {
                    catUcb.computeIfAbsent(r.category, bandit::score);
                }
            }
            List<RecallItem> out = new ArrayList<>(rows.size());
            for (Row r : rows) {
                // bandit:类目 UCB 主导,+0.001/rn 用类目内热度名次细分;关闭则退回按热度打分
                double score = useBandit
                        ? catUcb.getOrDefault(r.category, 0.0) + 0.001 / r.rn
                        : r.popularity;
                out.add(new RecallItem(r.itemId, score, RecallChannel.COLD));
            }
            return out;
        } catch (Exception e) {
            log.warn("冷启动探索召回失败: {}", e.getMessage());
            return List.of();
        }
    }

    private record Row(long itemId, double popularity, String category, int rn) {
    }
}
