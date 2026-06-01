package com.recsys.recall.channel;

import com.pgvector.PGvector;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量召回:取 user_embedding 中该用户的向量,在 item_embedding 中找余弦最近邻。
 * 用户无向量(新用户/冷启动)时返回空,交由热门/标签兜底。
 */
@Component
public class VectorRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(VectorRecaller.class);

    private final JdbcTemplate jdbc;
    private final RecallProperties props;

    public VectorRecaller(JdbcTemplate jdbc, RecallProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.VECTOR;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        float[] userVec = loadUserVector(ctx.userId());
        if (userVec == null) {
            return List.of();
        }
        int limit = props.getQuota().getVector();
        PGvector pv = new PGvector(userVec);
        // <=> 余弦距离;sim = 1 - 距离
        return jdbc.query(
                "SELECT item_id, 1 - (embedding <=> ?) AS sim " +
                "FROM item_embedding ORDER BY embedding <=> ? LIMIT ?",
                ps -> {
                    ps.setObject(1, pv);
                    ps.setObject(2, pv);
                    ps.setInt(3, limit);
                },
                (rs, n) -> new RecallItem(rs.getLong("item_id"), rs.getDouble("sim"), RecallChannel.VECTOR));
    }

    private float[] loadUserVector(long userId) {
        try {
            List<PGvector> rows = jdbc.query(
                    "SELECT embedding FROM user_embedding WHERE user_id=?",
                    (rs, n) -> (PGvector) rs.getObject("embedding"), userId);
            if (rows.isEmpty() || rows.get(0) == null) {
                return null;
            }
            return rows.get(0).toArray();
        } catch (Exception e) {
            // user_embedding 列若未注册 PGvector 类型,降级用文本解析
            log.debug("读取 user_embedding 失败,尝试文本解析: {}", e.getMessage());
            return loadUserVectorAsText(userId);
        }
    }

    private float[] loadUserVectorAsText(long userId) {
        List<String> rows = jdbc.query(
                "SELECT embedding::text FROM user_embedding WHERE user_id=?",
                (rs, n) -> rs.getString(1), userId);
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        String s = rows.get(0).replace("[", "").replace("]", "");
        String[] parts = s.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i].trim());
        }
        return v;
    }
}
