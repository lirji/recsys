package com.recsys.recall.channel;

import com.pgvector.PGvector;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量召回:取 user_embedding 中该用户的向量,在 item_embedding 中找余弦最近邻。
 * 用户无向量(新用户/冷启动)时返回空,交由热门/标签兜底。
 *
 * <p><b>熔断(E7)</b>:pgvector ANN 是重外呼,pg 宕机时若每请求都等到召回超时,会拖垮整条链路。
 * 用 Resilience4j 熔断器 {@code pgvector-recall} 包住 {@link #recall}——连续失败率超阈值即打开,
 * 后续请求<b>快速失败</b>到 {@link #recallFallback}(返回空,其它召回路兜底),半开后自动探测恢复。
 * 范式同 embedding 的 {@code GeminiEmbeddingClient};可同法套到 SEMANTIC/TWO_TOWER 等 pgvector 路。
 */
@Component
public class VectorRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(VectorRecaller.class);

    private final JdbcTemplate jdbc;
    private final RecallProperties props;

    public VectorRecaller(@org.springframework.beans.factory.annotation.Qualifier("derivedJdbc")
                          JdbcTemplate jdbc, RecallProperties props) {   // #3:向量读走派生库(默认 recsys)
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.VECTOR;
    }

    @Override
    @CircuitBreaker(name = "pgvector-recall", fallbackMethod = "recallFallback")
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

    /** 熔断打开 / ANN 异常时的兜底:返回空,交由其它召回路(HOT 等)补齐。 */
    @SuppressWarnings("unused")
    private List<RecallItem> recallFallback(RecallContext ctx, Throwable t) {
        log.warn("VECTOR 召回熔断/失败 user={},本次返回空(其它路兜底): {}", ctx.userId(), t.toString());
        return List.of();
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
