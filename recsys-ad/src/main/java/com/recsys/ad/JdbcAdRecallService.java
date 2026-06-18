package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.AdChannel;
import com.recsys.common.ad.AdRecallContext;
import com.recsys.common.ad.AdRecallService;
import com.recsys.common.ad.BidwordInvCodec;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.query.StructuredQuery;
import com.recsys.common.query.TermWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 广告召回:query→ad 多路并行,合并去重。每路独立降级(架构铁律)。
 *
 * <ul>
 *   <li><b>KW_EXACT/BROAD</b>:词项 + 改写词查竞价词倒排。优先读 Redis {@code bidword:inv:{keyword}}
 *       (自包含 member,见 {@link BidwordInvCodec}),Redis 整体不可用/空 → 回退 {@link AdRepository#kwByDb}。</li>
 *   <li><b>SEMANTIC_AD</b>:query 向量 → ad_embedding ANN。{@code query.embedding} 为 null(当前 Gemini 403)
 *       时直接跳过,不报错。</li>
 *   <li><b>HOT_AD</b>:高 quality×bid 兜底,保填充率。</li>
 * </ul>
 *
 * <p>合并:同一 adId 被多路命中时,保留优先级最高的主路({@link AdChannel#priority()} 小者优先),
 * 召回分取最大。
 */
@Service
public class JdbcAdRecallService implements AdRecallService {

    private static final Logger log = LoggerFactory.getLogger(JdbcAdRecallService.class);

    private final AdRepository repo;
    private final StringRedisTemplate redis;
    private final AdProperties props;

    public JdbcAdRecallService(AdRepository repo, StringRedisTemplate redis, AdProperties props) {
        this.repo = repo;
        this.redis = redis;
        this.props = props;
    }

    @Override
    public List<AdCandidate> recall(AdRecallContext ctx) {
        StructuredQuery q = ctx.query();
        Map<Long, AdCandidate> merged = new LinkedHashMap<>();

        // 1. 关键词路(原始词项 + 改写词)
        if (q != null && (ctx.isEnabled(AdChannel.KW_EXACT) || ctx.isEnabled(AdChannel.KW_BROAD))) {
            Set<String> terms = new LinkedHashSet<>();
            for (TermWeight tw : q.terms()) {
                terms.add(tw.term());
            }
            Set<String> all = new LinkedHashSet<>(terms);
            all.addAll(q.rewrites());
            for (AdCandidate c : keyword(all, terms, props.getRecall().getKw())) {
                mergeKeep(merged, c);
            }
        }

        // 2. 语义路(query 向量可用时)
        if (q != null && q.hasEmbedding() && ctx.isEnabled(AdChannel.SEMANTIC_AD)) {
            for (AdCandidate c : repo.semantic(q.embedding(), props.getRecall().getSemantic())) {
                mergeKeep(merged, c);
            }
        }

        // 3. 兜底路
        if (ctx.isEnabled(AdChannel.HOT_AD)) {
            for (AdCandidate c : repo.hot(props.getRecall().getHot())) {
                mergeKeep(merged, c);
            }
        }

        return new ArrayList<>(merged.values());
    }

    /** 关键词召回:Redis 倒排优先,整体不可用/空时回退 DB。 */
    private List<AdCandidate> keyword(Set<String> keywords, Set<String> exactTerms, int limit) {
        if (keywords.isEmpty()) {
            return List.of();
        }
        List<AdCandidate> fromRedis = keywordRedis(keywords, exactTerms, limit);
        if (!fromRedis.isEmpty()) {
            return fromRedis;
        }
        return repo.kwByDb(keywords, exactTerms, limit);
    }

    private List<AdCandidate> keywordRedis(Set<String> keywords, Set<String> exactTerms, int limit) {
        try {
            List<AdCandidate> out = new ArrayList<>();
            for (String kw : keywords) {
                AdChannel ch = exactTerms.contains(kw) ? AdChannel.KW_EXACT : AdChannel.KW_BROAD;
                Set<ZSetOperations.TypedTuple<String>> hits =
                        redis.opsForZSet().reverseRangeWithScores(RedisKeys.bidwordInv(kw), 0, limit - 1);
                if (hits == null) {
                    continue;
                }
                for (var t : hits) {
                    if (t.getValue() == null) {
                        continue;
                    }
                    AdCandidate c = BidwordInvCodec.decode(
                            t.getValue(), t.getScore() == null ? 0 : t.getScore(), ch);
                    if (c != null) {
                        out.add(c);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("读竞价词倒排 Redis 失败,回退 DB: {}", e.getMessage());
            return List.of();
        }
    }

    /** 合并:保留优先级更高的主路(priority 小者),召回分取最大。 */
    private static void mergeKeep(Map<Long, AdCandidate> merged, AdCandidate c) {
        merged.merge(c.adId(), c, (old, cur) -> {
            AdChannel ch = old.channel().priority() <= cur.channel().priority()
                    ? old.channel() : cur.channel();
            double score = Math.max(old.recallScore(), cur.recallScore());
            // bid/quality 以更可信的关键词路为准(KW 路 bid 来自具体竞价词)
            AdCandidate base = old.channel().priority() <= cur.channel().priority() ? old : cur;
            return new AdCandidate(base.adId(), base.itemId(), base.advertiserId(), base.bidwordId(),
                    score, ch, base.bid(), base.quality());
        });
    }
}
