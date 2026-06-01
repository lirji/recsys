package com.recsys.recall.channel;

import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 冷启动类目探索召回:按 {@code item.category} 分组,每类目取热度 Top-k,跨类目铺开。
 *
 * <p>用于新用户(无历史)的兴趣探索——纯热门会全是同一两个大类目,无法试探用户偏好;
 * 本路用窗口函数在每个类目内取前 k 名,并按 rank 升序交错(各类目 rank1、再 rank2…),
 * 让结果尽量覆盖更多类目,最大化探索面。命中后用户行为回流,后续即可走个性化路。
 *
 * <p>该路默认仅由编排在冷启动判定命中时启用(见 enabledChannels),普通请求不会触发。
 */
@Component
public class ColdStartRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(ColdStartRecaller.class);

    private final JdbcTemplate jdbc;
    private final RecallProperties props;

    public ColdStartRecaller(JdbcTemplate jdbc, RecallProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.COLD;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        int perCat = props.getQuota().getColdPerCategory();
        int limit = props.getQuota().getCold();
        try {
            // 每个类目内按热度排名,rn 升序交错铺开 → 最大化类目覆盖
            return jdbc.query(
                    "SELECT item_id, popularity FROM (" +
                    "  SELECT item_id, popularity, " +
                    "         ROW_NUMBER() OVER (PARTITION BY category ORDER BY popularity DESC) rn " +
                    "  FROM item WHERE category IS NOT NULL" +
                    ") t WHERE rn <= ? ORDER BY rn ASC, popularity DESC LIMIT ?",
                    (rs, n) -> new RecallItem(rs.getLong("item_id"), rs.getDouble("popularity"), RecallChannel.COLD),
                    perCat, limit);
        } catch (Exception e) {
            log.warn("冷启动探索召回失败: {}", e.getMessage());
            return List.of();
        }
    }
}
