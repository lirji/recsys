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
 * 词法召回(BM25 / 全文检索),搜索场景的"关键词匹配"路。与 {@link SemanticRecaller} 的
 * 向量语义路互补:词法精确命中 query 词面(片名/类目),向量召回近义/语义相关。二者经
 * RRF(Reciprocal Rank Fusion)在 {@link com.recsys.recall.MultiChannelRecallService} 融合 ——
 * 这就是业界"混合检索(hybrid search)"。
 *
 * <p><b>实现</b>:Postgres 全文检索 —— {@code item.title_tsv}(标题+类目的 tsvector,生成列+GIN 索引)
 * 对 {@code plainto_tsquery} 做 {@code ts_rank_cd} 打分(BM25 风格的词频/邻近度排序)。
 *
 * <p><b>仅 query 驱动场景生效</b>:{@code ctx.params["query"]} 为空(普通 userId 推荐)直接返回空。
 * <b>优雅降级</b>:{@code title_tsv} 列缺失 / DB 异常 → 返回空,其它路兜底。
 */
@Component
public class LexicalRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(LexicalRecaller.class);

    private final JdbcTemplate jdbc;
    private final RecallProperties props;

    public LexicalRecaller(JdbcTemplate jdbc, RecallProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.LEXICAL;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        String query = ctx.params().get("query");
        if (query == null || query.isBlank()) {
            return List.of();   // 仅在 query 驱动(搜索)时启用
        }
        try {
            int limit = props.getQuota().getLexical();
            // OR 语义提高召回:plainto_tsquery 会把词项用 & 连接(太严),改成 | (任一词命中)。
            // NULLIF 兜底全停用词时空串(to_tsquery('') 会报错 → NULL 则无匹配,优雅退空)。
            return jdbc.query(
                    "WITH q AS (SELECT to_tsquery('english', " +
                    "    NULLIF(replace(plainto_tsquery('english', ?)::text, '&', '|'), '')) AS tsq) " +
                    "SELECT item_id, ts_rank_cd(title_tsv, q.tsq) AS score " +
                    "FROM item, q WHERE title_tsv @@ q.tsq ORDER BY score DESC LIMIT ?",
                    ps -> {
                        ps.setString(1, query);
                        ps.setInt(2, limit);
                    },
                    (rs, n) -> new RecallItem(rs.getLong("item_id"), rs.getDouble("score"), RecallChannel.LEXICAL));
        } catch (Exception e) {
            log.debug("词法召回失败 q=[{}](title_tsv 缺失?),返回空: {}", query, e.getMessage());
            return List.of();
        }
    }
}
