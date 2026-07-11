package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.AdChannel;
import com.recsys.common.ad.AdRecallContext;
import com.recsys.common.ad.AdRecallService;
import com.recsys.common.ad.BidwordInvCodec;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.query.StructuredQuery;
import com.recsys.common.query.TermWeight;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 广告召回:query→ad 多路并行,合并去重。每路独立降级(架构铁律)。
 *
 * <ul>
 *   <li><b>KW_EXACT/BROAD</b>:词项 + 改写词查竞价词倒排。优先读 Redis {@code bidword:inv:{keyword}}
 *       (自包含 member,见 {@link BidwordInvCodec}),Redis 整体不可用/空 → 回退 {@link AdRepository#kwByDb}。</li>
 *   <li><b>SEMANTIC_AD</b>:query 向量 → ad_embedding ANN。{@code query.embedding} 为 null(当前 Gemini 403)
 *       时直接跳过,不报错。</li>
 *   <li><b>U2A</b>:用户长期兴趣向量({@code user_embedding})→ ad_embedding ANN,query 无关的个性化定向补充。
 *       新用户/无向量时跳过。</li>
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
    // 小线程池:四路召回相互独立 → 并行执行(而非串行四路之和)。HOT 全表扫因此与 keyword/u2a 重叠,
    // 不再叠加到关键词路后面。每路 fail-soft(异常当空),兑现类契约"任一路失败不影响整体"。
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ad-recall");
        t.setDaemon(true);
        return t;
    });

    public JdbcAdRecallService(AdRepository repo, StringRedisTemplate redis, AdProperties props) {
        this.repo = repo;
        this.redis = redis;
        this.props = props;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    @Override
    public List<AdCandidate> recall(AdRecallContext ctx) {
        StructuredQuery q = ctx.query();

        // 四路并行发起(各自内部判 enable,不启用则返回空);随后按固定顺序 keyword→semantic→u2a→hot
        // 合并——mergeKeep 冲突解析只看 channel 优先级/取 max,与合并顺序无关;LinkedHashMap 插入顺序也
        // 与串行一致,故输出(集合 + 顺序)与原串行逐项相等,纯粹是把四路的等待重叠起来。
        CompletableFuture<List<AdCandidate>> fKw = supply(() -> keywordPath(ctx, q));
        CompletableFuture<List<AdCandidate>> fSem = supply(() -> semanticPath(ctx, q));
        CompletableFuture<List<AdCandidate>> fU2a = supply(() -> u2aPath(ctx));
        CompletableFuture<List<AdCandidate>> fHot = supply(() -> hotPath(ctx));

        Map<Long, AdCandidate> merged = new LinkedHashMap<>();
        for (List<AdCandidate> part : List.of(fKw.join(), fSem.join(), fU2a.join(), fHot.join())) {
            for (AdCandidate c : part) {
                mergeKeep(merged, c);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** 提交一路召回到线程池,fail-soft:该路异常/降级时返回空,不拖累其余路(架构铁律)。 */
    private CompletableFuture<List<AdCandidate>> supply(Supplier<List<AdCandidate>> path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return path.get();
            } catch (Exception e) {
                log.debug("广告召回某路失败,跳过: {}", e.getMessage());
                return List.of();
            }
        }, pool);
    }

    /** 1. 关键词路(原始词项 + 改写词)。 */
    private List<AdCandidate> keywordPath(AdRecallContext ctx, StructuredQuery q) {
        if (q == null || !(ctx.isEnabled(AdChannel.KW_EXACT) || ctx.isEnabled(AdChannel.KW_BROAD))) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (TermWeight tw : q.terms()) {
            terms.add(tw.term());
        }
        Set<String> all = new LinkedHashSet<>(terms);
        all.addAll(q.rewrites());
        return keyword(all, terms, props.getRecall().getKw());
    }

    /** 2. 语义路(query 向量可用时)。 */
    private List<AdCandidate> semanticPath(AdRecallContext ctx, StructuredQuery q) {
        if (q == null || !q.hasEmbedding() || !ctx.isEnabled(AdChannel.SEMANTIC_AD)) {
            return List.of();
        }
        return repo.semantic(q.embedding(), props.getRecall().getSemantic());
    }

    /** 3. U2A 定向路(用户长期向量 → ad_embedding,query 无关的个性化补充)。 */
    private List<AdCandidate> u2aPath(AdRecallContext ctx) {
        if (ctx.userId() <= 0 || !ctx.isEnabled(AdChannel.U2A)) {
            return List.of();
        }
        return repo.u2a(ctx.userId(), props.getRecall().getU2a());
    }

    /** 4. 兜底路。 */
    private List<AdCandidate> hotPath(AdRecallContext ctx) {
        if (!ctx.isEnabled(AdChannel.HOT_AD)) {
            return List.of();
        }
        return repo.hot(props.getRecall().getHot());
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
