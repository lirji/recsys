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
 * 标签召回:读 app_user.profile(JSONB)拿偏好类目,按 item.category 取热门物品。
 * profile 约定:{"categories":["Comedy","Action"]}。无画像时返回空。
 */
@Component
public class TagRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(TagRecaller.class);

    private final JdbcTemplate jdbc;
    private final RecallProperties props;

    public TagRecaller(JdbcTemplate jdbc, RecallProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.TAG;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        List<String> categories = preferredCategories(ctx.userId());
        if (categories.isEmpty()) {
            return List.of();
        }
        int limit = props.getQuota().getTag();
        // 偏好类目下按热度取物品;category 命中即可
        String placeholders = String.join(",", categories.stream().map(c -> "?").toList());
        Object[] params = new Object[categories.size() + 1];
        for (int i = 0; i < categories.size(); i++) {
            params[i] = categories.get(i);
        }
        params[categories.size()] = limit;
        return jdbc.query(
                "SELECT item_id, popularity FROM item WHERE category IN (" + placeholders + ") " +
                "ORDER BY popularity DESC LIMIT ?",
                (rs, n) -> new RecallItem(rs.getLong("item_id"), rs.getDouble("popularity"), RecallChannel.TAG),
                params);
    }

    private List<String> preferredCategories(long userId) {
        try {
            // 从 JSONB profile 的 categories 数组取值
            return jdbc.queryForList(
                    "SELECT jsonb_array_elements_text(profile->'categories') FROM app_user WHERE user_id=?",
                    String.class, userId);
        } catch (Exception e) {
            log.debug("读取用户偏好类目失败: {}", e.getMessage());
            return List.of();
        }
    }
}
