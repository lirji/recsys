package com.recsys.recengine;

import com.recsys.common.rank.RankedItem;
import com.recsys.rank.RankRouter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 排序影子流量(P5):新排序策略上线前的<b>零风险在线灰度评估</b>。
 *
 * <p>按 {@code recsys.shadow.sampleRate} 对部分请求,在<b>后台线程异步</b>用影子策略对<b>同一批候选</b>
 * 再排一次,只记录"与主策略 top-K 的重合度 + 影子耗时",<b>绝不进入用户返回</b>。与 A/B 的 rank 层
 * (真实分流的金丝雀,影响用户结果)互补:影子可在不担任何线上风险的前提下,全量观测新旧模型的排序差异。
 *
 * <p>指标(Prometheus):{@code recsys.rank.shadow.overlap}(top-K 重合率,tag shadow=策略名)、
 * {@code recsys.rank.shadow.duration}(影子耗时)、{@code recsys.rank.shadow.runs}。
 * 采样按 userId 确定性哈希 → 同一用户稳定进出影子集,便于横向对比。
 */
@Component
public class ShadowRankRunner {

    private static final Logger log = LoggerFactory.getLogger(ShadowRankRunner.class);

    private final RankRouter rankRouter;
    private final MeterRegistry meterRegistry;
    private final RecEngineProperties props;
    // 单独的小线程池:影子排序不占主链路,队列满则丢弃(影子是尽力而为,不保证每请求都跑)
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "shadow-rank");
        t.setDaemon(true);
        return t;
    });

    public ShadowRankRunner(RankRouter rankRouter, MeterRegistry meterRegistry, RecEngineProperties props) {
        this.rankRouter = rankRouter;
        this.meterRegistry = meterRegistry;
        this.props = props;
    }

    /**
     * 若命中采样,异步用影子策略重排候选并与主结果对比打点。非阻塞、异常吞掉,绝不影响主链路。
     *
     * @param primaryRanked 主策略已排好的结果(取 top-K 与影子对比)
     */
    public void maybeShadow(long userId, List<Long> candidateIds, String scene,
                            String primaryStrategy, List<RankedItem> primaryRanked) {
        RecEngineProperties.Shadow cfg = props.getShadow();
        String shadow = cfg.getStrategy();
        if (!cfg.isEnabled() || shadow == null || shadow.isBlank()
                || shadow.equalsIgnoreCase(primaryStrategy)
                || candidateIds == null || candidateIds.isEmpty()) {
            return;
        }
        if (!sampled(userId, cfg.getSampleRate())) {
            return;
        }
        // 快照主结果 top-K(主链程内取,避免与后续修改竞态)
        List<Long> primaryTopK = topK(primaryRanked, cfg.getK());
        try {
            pool.submit(() -> runShadow(userId, candidateIds, scene, shadow, primaryTopK, cfg.getK()));
        } catch (RuntimeException reject) {
            // 线程池饱和 → 丢弃本次影子(尽力而为)
            log.debug("影子排序线程池饱和,丢弃 user={}", userId);
        }
    }

    private void runShadow(long userId, List<Long> candidateIds, String scene,
                           String shadowStrategy, List<Long> primaryTopK, int k) {
        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            List<RankedItem> shadowRanked = rankRouter.rank(userId, candidateIds, scene, shadowStrategy);
            sample.stop(Timer.builder("recsys.rank.shadow.duration")
                    .tag("shadow", shadowStrategy).register(meterRegistry));

            double overlap = overlapAtK(primaryTopK, topK(shadowRanked, k));
            meterRegistry.summary("recsys.rank.shadow.overlap", "shadow", shadowStrategy).record(overlap);
            meterRegistry.counter("recsys.rank.shadow.runs", "shadow", shadowStrategy).increment();
            log.debug("影子排序 user={} 策略={} top{} 重合率={}", userId, shadowStrategy, k, overlap);
        } catch (Exception e) {
            log.debug("影子排序失败 user={} 策略={}: {}", userId, shadowStrategy, e.getMessage());
        }
    }

    /** 按 userId 确定性采样:hash 落 [0,1) < rate 命中。稳定,同一用户始终同进同出影子集。 */
    private static boolean sampled(long userId, double rate) {
        if (rate <= 0) {
            return false;
        }
        if (rate >= 1) {
            return true;
        }
        long h = mix(userId * 0x9E3779B97F4A7C15L + 0x1234567);
        double u = (h >>> 11) / (double) (1L << 53);   // [0,1)
        return u < rate;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static List<Long> topK(List<RankedItem> ranked, int k) {
        if (ranked == null || ranked.isEmpty()) {
            return List.of();
        }
        int n = Math.min(k, ranked.size());
        java.util.List<Long> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(ranked.get(i).itemId());
        }
        return out;
    }

    /** top-K 重合率 = |A ∩ B| / max(|A|,|B|)(两侧都空 → 1.0)。纯函数,供单测。 */
    static double overlapAtK(List<Long> a, List<Long> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        Set<Long> sa = new HashSet<>(a);
        int inter = 0;
        for (Long x : new HashSet<>(b)) {
            if (sa.contains(x)) {
                inter++;
            }
        }
        int denom = Math.max(sa.size(), new HashSet<>(b).size());
        return denom == 0 ? 1.0 : (double) inter / denom;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
