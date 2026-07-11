package com.recsys.recall;

import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.common.recall.RecallService;
import com.recsys.recall.channel.ChannelRecaller;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 多路召回合并。并行调用各路 ChannelRecaller,合并去重。
 *
 * 设计要点(docs/03 §1):
 * - 各路 recallScore 量纲不同(SEMANTIC/VECTOR 是余弦∈[0,1],HOT/TAG/COLD 是热度计数),
 *   合并前**先按每路自身最大值归一化到 [0,1]**,使跨路分数可比 —— 否则下游全局归一化会被
 *   热度计数(动辄成百上千)主导,把语义/向量路的余弦分压到接近 0,channel-boost 也救不回来。
 * - 同一 itemId 命中多路 → 保留所有来源(channels),recallScore 取各路归一化分的最大值。
 * - 任一路抛异常被 catch 降级,不影响整体;热门兜底始终生效。
 */
@Service
@EnableConfigurationProperties(RecallProperties.class)
public class MultiChannelRecallService implements RecallService {

    private static final Logger log = LoggerFactory.getLogger(MultiChannelRecallService.class);

    private final List<ChannelRecaller> recallers;
    private final RecallProperties props;
    private final ExecutorService pool;

    public MultiChannelRecallService(List<ChannelRecaller> recallers, RecallProperties props) {
        this.recallers = recallers;
        this.props = props;
        int size = Math.max(1, props.getParallel().getPoolSize());
        this.pool = Executors.newFixedThreadPool(size, r -> {
            Thread t = new Thread(r, "recall-worker");
            t.setDaemon(true);   // 守护线程:不阻止 JVM 退出
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        // 融合模式:默认按路归一化取 max(跨路可比);搜索场景传 recall-fusion=rrf 用 RRF
        // (Reciprocal Rank Fusion)按各路排名融合 —— 对量纲迥异的检索器(BM25 vs 余弦)更鲁棒。
        boolean rrf = "rrf".equalsIgnoreCase(ctx.params().get("recall-fusion"));

        // 启用的召回路(分层 A/B / 冷启动可只启用部分;enabledChannels 为空=全开)
        List<ChannelRecaller> active = new ArrayList<>();
        for (ChannelRecaller r : recallers) {
            if (ctx.isEnabled(r.channel())) {
                active.add(r);
            }
        }
        // 各路并行调用(有界线程池 + 单路超时);任一路超时/异常当空,不阻断其余路与合并。
        List<List<RecallItem>> perChannel = runRecallers(active, ctx);

        // explain(可空 sink):记去重前每路原始召回数。active 与 perChannel 索引对齐,故直接取 size()。
        if (ctx.explain() != null) {
            for (int i = 0; i < active.size(); i++) {
                List<RecallItem> ch = perChannel.get(i);
                ctx.explain().record(active.get(i).channel(), ch == null ? 0 : ch.size());
            }
        }

        // itemId -> 合并后的条目(记录来源与召回分)。合并逻辑与串/并行无关,顺序与 active 对齐(结果确定)。
        Map<Long, Merged> merged = new LinkedHashMap<>();
        for (List<RecallItem> items : perChannel) {
            if (items == null || items.isEmpty()) {
                continue;
            }
            if (rrf) {
                mergeRrf(merged, items);
            } else {
                mergeNormalized(merged, items);
            }
        }

        // RRF 累加分归一化到 [0,1],与默认模式量纲一致(下游全局归一化 + channel-boost 不受影响)
        if (rrf && !merged.isEmpty()) {
            double max = merged.values().stream().mapToDouble(m -> m.score).max().orElse(1.0);
            double d = max > 0 ? max : 1.0;
            for (Merged m : merged.values()) {
                m.score /= d;
            }
        }

        List<RecallItem> out = new ArrayList<>(merged.size());
        for (var e : merged.entrySet()) {
            Merged m = e.getValue();
            // 主召回路按信息量优先级选取(而非命中顺序):个性化路 > 偏好/探索 > 热门兜底,
            // 让推荐理由更贴切(如一个热门片同时被 I2I 命中,应标 I2I 而非 HOT)
            RecallChannel primary = m.channels.stream()
                    .min(java.util.Comparator.comparingInt(MultiChannelRecallService::priority))
                    .orElse(m.channels.get(0));
            // 主路按优先级排首位,其余按命中顺序跟随,全集透传给下游(理由/调试/未来融合加权)
            List<RecallChannel> ordered = new ArrayList<>();
            ordered.add(primary);
            for (RecallChannel c : m.channels) {
                if (c != primary) {
                    ordered.add(c);
                }
            }
            out.add(new RecallItem(e.getKey(), m.score, primary, ordered));
        }
        log.debug("用户 {} 多路召回合并后候选 {} 个", ctx.userId(), out.size());
        return out;
    }

    /** 并行(可配)调用各路召回;单路超时/异常/返回 null 一律当空 List,输出顺序与 active 对齐。 */
    private List<List<RecallItem>> runRecallers(List<ChannelRecaller> active, RecallContext ctx) {
        RecallProperties.Parallel p = props.getParallel();
        List<List<RecallItem>> out = new ArrayList<>(active.size());
        if (!p.isEnabled()) {
            // 串行兜底(可回退开关)
            for (ChannelRecaller r : active) {
                out.add(safeRecall(r, ctx));
            }
            return out;
        }
        List<CompletableFuture<List<RecallItem>>> futures = new ArrayList<>(active.size());
        for (ChannelRecaller r : active) {
            futures.add(CompletableFuture.supplyAsync(() -> safeRecall(r, ctx), pool));
        }
        for (int i = 0; i < futures.size(); i++) {
            try {
                out.add(futures.get(i).get(p.getTimeoutMs(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException te) {
                log.warn("召回路 {} 超时 {}ms,当空处理", active.get(i).channel(), p.getTimeoutMs());
                futures.get(i).cancel(true);
                out.add(List.of());
            } catch (Exception e) {
                log.warn("召回路 {} 异常,当空: {}", active.get(i).channel(), e.getMessage());
                out.add(List.of());
            }
        }
        return out;
    }

    /** 单路召回的异常/空保护:永远返回非 null List(在工作线程内执行,异常不外泄)。 */
    private List<RecallItem> safeRecall(ChannelRecaller r, RecallContext ctx) {
        try {
            List<RecallItem> items = r.recall(ctx);
            return items == null ? List.of() : items;
        } catch (Exception e) {
            log.warn("召回路 {} 失败,跳过: {}", r.getClass().getSimpleName(), e.getMessage());
            return List.of();
        }
    }

    /** RRF:本路按分降序定名次,贡献 1/(k+rank) 累加;名次而非原始分,免归一化、抗量纲。 */
    private void mergeRrf(Map<Long, Merged> merged, List<RecallItem> items) {
        double rrfK = props.getRrfK();
        List<RecallItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Double.compare(b.recallScore(), a.recallScore()));
        int rank = 0;
        for (RecallItem it : sorted) {
            rank++;
            double contrib = 1.0 / (rrfK + rank);
            merged.compute(it.itemId(), (id, cur) -> {
                if (cur == null) {
                    return new Merged(contrib, it.channel());
                }
                cur.score += contrib;
                if (!cur.channels.contains(it.channel())) {
                    cur.channels.add(it.channel());
                }
                return cur;
            });
        }
    }

    /** 按路归一化:把本路所有分除以本路最大分,归一化到 [0,1](跨路可比的前提),同物品跨路取 max。 */
    private void mergeNormalized(Map<Long, Merged> merged, List<RecallItem> items) {
        double chMax = 0;
        for (RecallItem it : items) {
            chMax = Math.max(chMax, it.recallScore());
        }
        final double denom = chMax > 0 ? chMax : 1.0;
        for (RecallItem it : items) {
            double norm = it.recallScore() / denom;
            merged.compute(it.itemId(), (id, cur) -> {
                if (cur == null) {
                    return new Merged(norm, it.channel());
                }
                cur.score = Math.max(cur.score, norm);
                if (!cur.channels.contains(it.channel())) {
                    cur.channels.add(it.channel());
                }
                return cur;
            });
        }
    }

    /** 主召回路优先级(数字越小越优先);兜底性越强越靠后。 */
    private static int priority(RecallChannel c) {
        return switch (c) {
            case I2I -> 0;
            case SWING -> 1;
            case U2U -> 2;
            case TWO_TOWER -> 3;   // 学行为的个性化向量召回,信息量高,优先于内容向量
            case TIGER -> 4;       // 完整生成式召回(自回归),学习型语义信号,信息量高
            case GENERATIVE -> 5;  // 生成式语义 ID 召回(前缀检索版)
            case VECTOR -> 6;
            case SEMANTIC -> 7;
            case LEXICAL -> 8;     // 词法 query 相关性(搜索),与 SEMANTIC 同属 query 内容匹配
            case TAG -> 9;
            case COLD -> 10;
            case HOT -> 11;
        };
    }

    /** 合并去重后,同一物品的来源集合 + 最大召回分。 */
    private static final class Merged {
        double score;
        final List<RecallChannel> channels = new ArrayList<>();

        Merged(double score, RecallChannel first) {
            this.score = score;
            this.channels.add(first);
        }
    }
}
