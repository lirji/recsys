package com.recsys.recall.channel;

import com.pgvector.PGvector;
import com.recsys.common.content.ItemCatalogReader;
import com.recsys.common.embedding.EmbeddingClient;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 语义 query 召回:把一段文本向量化后在 {@code item_embedding} 做余弦最近邻。
 *
 * <p>query 来源:
 * <ul>
 *   <li>显式:{@code ctx.params["query"]}(如搜索场景传入);</li>
 *   <li>隐式(无 query):用该用户近期正反馈物品的标题拼成"伪 query",表达近期兴趣。</li>
 * </ul>
 *
 * <p>与 {@link VectorRecaller} 的区别:Vector 路用离线聚合的 user_embedding(长期画像),
 * Semantic 路按 query/近期标题实时向量化(即时意图),两路互补。
 *
 * <p>依赖在线 {@link EmbeddingClient}(Gemini,受免费配额限制),用 ObjectProvider 可选注入;
 * 客户端缺失/调用失败/无 query → 返回空,优雅降级不阻塞链路。
 */
@Component
public class SemanticRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(SemanticRecaller.class);

    private final JdbcTemplate jdbc;                 // item_embedding 向量 ANN(派生读模型,非 item 表,不切)
    private final ItemCatalogReader itemCatalog;     // #3:伪 query 取近期标题的 item 读经此 seam(db|replica)
    private final ObjectProvider<EmbeddingClient> embeddingProvider;
    private final RecallProperties props;

    public SemanticRecaller(@org.springframework.beans.factory.annotation.Qualifier("derivedJdbc")
                            JdbcTemplate jdbc,   // #3:item_embedding ANN 走派生库
                            ItemCatalogReader itemCatalog,
                            ObjectProvider<EmbeddingClient> embeddingProvider,
                            RecallProperties props) {
        this.jdbc = jdbc;
        this.itemCatalog = itemCatalog;
        this.embeddingProvider = embeddingProvider;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.SEMANTIC;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        EmbeddingClient embedding = embeddingProvider.getIfAvailable();
        if (embedding == null) {
            return List.of();
        }
        String query = resolveQuery(ctx);
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            float[] vec = embedding.embedText(query);
            if (vec == null || vec.length == 0) {
                return List.of();
            }
            int limit = props.getQuota().getSemantic();
            PGvector pv = new PGvector(vec);
            return jdbc.query(
                    "SELECT item_id, 1 - (embedding <=> ?) AS sim " +
                    "FROM item_embedding ORDER BY embedding <=> ? LIMIT ?",
                    ps -> {
                        ps.setObject(1, pv);
                        ps.setObject(2, pv);
                        ps.setInt(3, limit);
                    },
                    (rs, n) -> new RecallItem(rs.getLong("item_id"), rs.getDouble("sim"), RecallChannel.SEMANTIC));
        } catch (Exception e) {
            log.warn("语义召回失败(可能是 embedding 配额/网络),降级返回空 user={}: {}",
                    ctx.userId(), e.getMessage());
            return List.of();
        }
    }

    /** 显式 query 优先;否则用近期正反馈物品标题拼伪 query。 */
    private String resolveQuery(RecallContext ctx) {
        String explicit = ctx.params().get("query");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        List<String> titles = itemCatalog.recentTitles(ctx.userId(), 10);
        if (titles.isEmpty()) {
            return null;
        }
        return String.join(" ", titles);
    }
}
