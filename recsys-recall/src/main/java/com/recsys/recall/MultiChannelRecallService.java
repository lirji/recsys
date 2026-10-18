package com.recsys.recall;

import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.common.recall.RecallService;
import com.recsys.recall.channel.ChannelRecaller;
import com.recsys.recall.channel.RecentPositiveItemsSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * - 任一路抛异常降级为空，预算内完成的其他通道仍参与合并。
 * - <b>舱壁隔离</b>:慢通道(pgvector ANN/ONNX/重 DB)与快通道(HOT/TAG/I2I 等兜底)分池。慢路超时后
 *   {@code cancel(true)} 不中断阻塞调用、线程被占,但只占慢池 → 快池不被慢路占用，但自身过载仍会明确拒绝
 *   （实际上限与降级见C20）。可 {@code recsys.recall.parallel.bulkhead-enabled=false} 退回单池。
 */
@Service
@EnableConfigurationProperties(RecallProperties.class)
public class MultiChannelRecallService implements RecallService {

    private static final Logger log = LoggerFactory.getLogger(MultiChannelRecallService.class);

    /** 消费预取近期正反馈物品的召回路(三路 SQL 完全同口径,可共享一次查询)。 */
    private static final java.util.EnumSet<RecallChannel> SEED_CHANNELS =
            java.util.EnumSet.of(RecallChannel.I2I, RecallChannel.SWING, RecallChannel.GENERATIVE);
    private static final java.util.EnumSet<RecallChannel> SEQUENCE_CHANNELS =
            java.util.EnumSet.of(RecallChannel.MULTI_INTEREST);

    private final List<ChannelRecaller> recallers;
    private final RecallProperties props;
    private final RecentPositiveItemsSource recentItemsSource;
    private final BehaviorSequenceSource behaviorSequenceSource;
    private final com.recsys.recall.execution.RecallExecution execution;
    private final boolean ownsExecution;
    private final java.util.EnumSet<RecallChannel> slowChannelSet;

    /** 非Spring调用沿同一预算执行，shutdown释放自有资源。 */
    public MultiChannelRecallService(List<ChannelRecaller> recallers, RecallProperties props,
                                     RecentPositiveItemsSource recentItemsSource, BehaviorSequenceSource behaviorSequenceSource) {
        this(recallers, props, recentItemsSource, behaviorSequenceSource,
                new com.recsys.recall.execution.RecallExecution(props, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), true);
    }

    /** Spring持有共享资源生命周期，避免每个请求/调用方另建池。 */
    @org.springframework.beans.factory.annotation.Autowired
    public MultiChannelRecallService(List<ChannelRecaller> recallers, RecallProperties props,
                                     RecentPositiveItemsSource recentItemsSource, BehaviorSequenceSource behaviorSequenceSource,
                                     com.recsys.recall.execution.RecallExecution execution) {
        this(recallers, props, recentItemsSource, behaviorSequenceSource, execution, false);
    }

    private MultiChannelRecallService(List<ChannelRecaller> recallers, RecallProperties props,
                                     RecentPositiveItemsSource recentItemsSource, BehaviorSequenceSource behaviorSequenceSource,
                                     com.recsys.recall.execution.RecallExecution execution, boolean ownsExecution) {
        this.recallers = List.copyOf(recallers); this.props = props; this.recentItemsSource = recentItemsSource;
        this.behaviorSequenceSource = behaviorSequenceSource; this.execution = execution; this.ownsExecution = ownsExecution;
        this.slowChannelSet = parseSlowChannels(props.getParallel().getSlowChannels());
        if (recallers.stream().map(ChannelRecaller::channel).distinct().count() != recallers.size())
            throw new IllegalArgumentException(com.recsys.common.error.ErrorCode.VALIDATION_FAILED.displayMessage());
    }

    private java.util.EnumSet<RecallChannel> parseSlowChannels(String csv) {
        java.util.EnumSet<RecallChannel> set = java.util.EnumSet.noneOf(RecallChannel.class);
        if (csv == null) {
            return set;
        }
        for (String s : csv.split(",")) {
            String name = s.trim().toUpperCase();
            if (name.isEmpty()) {
                continue;
            }
            try {
                set.add(RecallChannel.valueOf(name));
            } catch (IllegalArgumentException e) {
                log.warn("slow-channels 含未知召回通道,已忽略: {}", name);
            }
        }
        return set;
    }

    private java.util.concurrent.ThreadPoolExecutor poolFor(RecallChannel channel) {
        return slowChannelSet.contains(channel) ? execution.slow : execution.fast;
    }

    @PreDestroy
    void shutdown() { if (ownsExecution) execution.close(); }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(props.getParallel().getTotalTimeoutMs());
        var request = execution.admit(ctx.userId());
        if (request == null) return List.of();
        try { return recallWithin(snapshot(ctx), request, deadline); }
        finally { request.close(); execution.duration(System.nanoTime() - started); }
    }

    /** 把调用方可变集合冻结；explain仍仅由请求线程合并写入。 */
    private RecallContext snapshot(RecallContext ctx) {
        return new RecallContext(ctx.userId(), ctx.size(), ctx.scene(), List.copyOf(ctx.enabledChannels()), Map.copyOf(ctx.params()),
                ctx.queryTerms(), ctx.explain(), ctx.recentPositiveItems() == null ? null : List.copyOf(ctx.recentPositiveItems()),
                ctx.behaviorSequence() == null ? null : List.copyOf(ctx.behaviorSequence()));
    }

    private List<RecallItem> recallWithin(RecallContext ctx, com.recsys.recall.execution.RecallExecution.Request request, long deadline) {
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
        // 每请求取一次用户近期正反馈物品下发各通道:消除 I2I/SWING/GENERATIVE 三路对 user_behavior 的
        // 重复查询与连接扇出。仅当尚未预取且确有种子路启用时才查(纯冷启动/HOT 路不触发,零额外开销)。
        if (active.stream().anyMatch(r -> SEED_CHANNELS.contains(r.channel()) || SEQUENCE_CHANNELS.contains(r.channel()))) {
            RecallContext original = ctx;
            long prefetchDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(props.getParallel().getPrefetchTimeoutMs()));
            var task = execution.submit(execution.prefetch, request, prefetchDeadline,
                    () -> {
                        var seeded = withPrefetchedSeeds(original, active);
                        // 一个不响应中断的查询晚返回后，不能再开启第二个已超预算的查询。
                        return Thread.currentThread().isInterrupted() || System.nanoTime() >= prefetchDeadline
                                ? seeded.withBehaviorSequence(List.of()) : withPrefetchedSequence(seeded, active);
                    });
            RecallContext prefetched = await(task);
            ctx = prefetched == null ? original.withRecentPositiveItems(List.of()).withBehaviorSequence(List.of()) : snapshot(prefetched);
        }
        // 各路并行调用(有界线程池 + 单路超时);任一路超时/异常当空,不阻断其余路与合并。
        List<List<RecallItem>> perChannel = runRecallers(active, ctx, request, deadline);

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

    /**
     * 若有种子路(I2I/SWING/GENERATIVE)启用且尚未预取,取一次用户近期正反馈物品填入上下文。
     * fail-soft:查询异常返回空种子，避免故障时各路重新查库放大压力。
     */
    private RecallContext withPrefetchedSeeds(RecallContext ctx, List<ChannelRecaller> active) {
        if (ctx.recentPositiveItems() != null) {
            return ctx;   // 上游(eval / 测试)已预取,不重复查
        }
        boolean needSeeds = active.stream().anyMatch(r -> SEED_CHANNELS.contains(r.channel()));
        if (!needSeeds) {
            return ctx;
        }
        int limit = Math.max(props.getQuota().getI2iSeed(), props.getQuota().getGenerativeSeed());
        try {
            List<Long> seeds = recentItemsSource.recentPositiveItems(ctx.userId(), limit);
            return ctx.withRecentPositiveItems(seeds);
        } catch (Exception e) {
            log.warn("预取用户近期正反馈物品失败,本次不重复回源: {}", e.getMessage());
            return ctx.withRecentPositiveItems(List.of());
        }
    }

    private RecallContext withPrefetchedSequence(RecallContext ctx, List<ChannelRecaller> active) {
        if (ctx.behaviorSequence() != null) {
            return ctx;
        }
        boolean needed = active.stream().anyMatch(r -> SEQUENCE_CHANNELS.contains(r.channel()));
        if (!needed) {
            return ctx;
        }
        try {
            return ctx.withBehaviorSequence(behaviorSequenceSource.sequence(
                    ctx.userId(), props.getMultiInterest().getMaxHistory()));
        } catch (Exception e) {
            log.warn("预取 MIND 行为序列失败,该路将空返回: {}", e.getMessage());
            return ctx.withBehaviorSequence(List.of());
        }
    }

    /** 共享绝对截止时间，排队也计入；到期后仍收集已按时完成的快路结果。 */
    private List<List<RecallItem>> runRecallers(List<ChannelRecaller> active, RecallContext ctx,
            com.recsys.recall.execution.RecallExecution.Request request, long deadline) {
        var tasks = new ArrayList<com.recsys.recall.execution.RecallExecution.Task<List<RecallItem>>>();
        List<List<RecallItem>> out = new ArrayList<>(active.size());
        try {
            for (ChannelRecaller recaller : active) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) { tasks.add(null); if (!props.getParallel().isEnabled()) out.add(List.of()); continue; }
                long channelDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(props.getParallel().getTimeoutMs()));
                var task = execution.submit(poolFor(recaller.channel()), request, channelDeadline, () -> safeRecall(recaller, ctx));
                tasks.add(task);
                if (!props.getParallel().isEnabled()) { var result = await(task); out.add(result == null ? List.of() : result); }
            }
            if (props.getParallel().isEnabled()) for (var task : tasks) { var result = await(task); out.add(result == null ? List.of() : result); }
            return out;
        } finally { for (var task : tasks) if (task != null && !task.isDone()) task.cancel(true); }
    }

    private <T> T await(com.recsys.recall.execution.RecallExecution.Task<T> task) {
        if (task == null) return null;
        try {
            long remaining = task.deadline - System.nanoTime();
            T result = task.isDone() ? task.get() : task.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
            if (task.completedAt() > task.deadline) { execution.count("late_result"); return null; }
            return result;
        } catch (InterruptedException interrupted) {
            task.cancel(true); Thread.currentThread().interrupt(); execution.count("interrupted"); return null;
        } catch (TimeoutException timeout) {
            task.cancel(true); execution.count("timeout"); return null;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException failed) {
            execution.count("failed"); return null;
        }
    }

    /** 单路召回的异常/空保护:永远返回非 null List(在工作线程内执行,异常不外泄)。 */
    private List<RecallItem> safeRecall(ChannelRecaller r, RecallContext ctx) {
        try {
            List<RecallItem> items = r.recall(ctx);
            return items == null ? List.of() : List.copyOf(items.subList(0, Math.min(items.size(), props.getParallel().getMaxResultsPerChannel())));
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
            case MULTI_INTEREST -> 4;
            case GRAPH -> 5;
            case TIGER -> 6;       // 完整生成式召回(自回归),学习型语义信号,信息量高
            case GENERATIVE -> 7;  // 生成式语义 ID 召回(前缀检索版)
            case VECTOR -> 8;
            case SEMANTIC -> 9;
            case LEXICAL -> 10;     // 词法 query 相关性(搜索),与 SEMANTIC 同属 query 内容匹配
            case TAG -> 11;
            case COLD -> 12;
            case HOT -> 13;
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
