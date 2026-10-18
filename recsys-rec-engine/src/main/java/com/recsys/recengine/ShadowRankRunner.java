package com.recsys.recengine;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.rank.RankedItem;
import com.recsys.rank.RankRouter;
import com.recsys.recall.execution.RecallExecution;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 尽力而为的影子排序：过载仅放弃比较样本，绝不把排序转到请求线程。
 * 队列/输入/实际worker有界；与主链路仍共享物理CPU和依赖，默认保持关闭。
 */
@Component
public class ShadowRankRunner {
    private static final Logger log = LoggerFactory.getLogger(ShadowRankRunner.class);
    private static final long SWEEP_MS = 10;
    private static final Set<String> STRATEGIES = Set.of("v1", "onnx", "deepfm", "dcn", "mmoe", "din", "dien", "sim", "ple");
    private final RankRouter rankRouter;
    private final MeterRegistry meterRegistry;
    private final RecEngineProperties props;
    private final ThreadPoolExecutor pool;
    private final ScheduledThreadPoolExecutor deadlines;
    private final ConcurrentMap<Long, Ticket> inFlight = new ConcurrentHashMap<>();
    private final Semaphore slots;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long timeoutNanos;
    private final int maxCandidates;

    /** Spring管理池生命周期；只有一个本地维护任务，避免每请求创建延迟任务。 */
    public ShadowRankRunner(RankRouter rankRouter, MeterRegistry meterRegistry, RecEngineProperties props) {
        var cfg = props.getShadow();
        if (cfg.getThreads() < 1 || cfg.getThreads() > 2 || cfg.getQueueCapacity() < 0 || cfg.getQueueCapacity() > 32
                || cfg.getTimeoutMs() < 1 || cfg.getTimeoutMs() > 1000 || cfg.getMaxCandidates() < 1 || cfg.getMaxCandidates() > 1024
                || !validSampling(cfg)) throw new IllegalArgumentException(ErrorCode.VALIDATION_FAILED.displayMessage());
        this.rankRouter = rankRouter; this.meterRegistry = meterRegistry; this.props = props;
        timeoutNanos = TimeUnit.MILLISECONDS.toNanos(cfg.getTimeoutMs()); maxCandidates = cfg.getMaxCandidates();
        slots = new Semaphore(cfg.getThreads() + cfg.getQueueCapacity());
        pool = RecallExecution.pool("shadow-rank", cfg.getThreads(), cfg.getQueueCapacity(), meterRegistry);
        deadlines = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "shadow-deadlines"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        deadlines.scheduleWithFixedDelay(this::expire, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS);
        meterRegistry.gauge("recsys.rank.shadow.inflight", inFlight, ConcurrentMap::size);
    }

    private static boolean validSampling(RecEngineProperties.Shadow cfg) {
        return Double.isFinite(cfg.getSampleRate()) && cfg.getSampleRate() >= 0 && cfg.getSampleRate() <= 1
                && cfg.getK() >= 1 && cfg.getK() <= 100;
    }

    /** 主线程仅冻结有界输入并提交；任何拒绝不改变主结果，也不处理关键业务事件。 */
    public void maybeShadow(long userId, List<Long> candidateIds, String scene,
                            String primaryStrategy, List<RankedItem> primaryRanked) {
        long deadline = System.nanoTime() + timeoutNanos;
        var cfg = props.getShadow();
        if (!cfg.isEnabled()) return;
        String strategy = cfg.getStrategy() == null ? "" : cfg.getStrategy().trim().toLowerCase(java.util.Locale.ROOT);
        if (!validSampling(cfg) || !STRATEGIES.contains(strategy)) { count("invalid_config"); return; }
        if (strategy.equalsIgnoreCase(primaryStrategy) || !sampled(userId, cfg.getSampleRate())) return;
        if (closed.get()) { count("closed"); return; }
        if (candidateIds == null || candidateIds.isEmpty()) return;
        if (candidateIds.size() > maxCandidates) { count("oversize"); return; }
        if (!slots.tryAcquire()) { count("saturated"); return; }
        var ticket = new Ticket(userId, deadline);
        synchronized (ticket) {
            if (inFlight.putIfAbsent(userId, ticket) != null) { slots.release(); count("user_busy"); return; }
            try {
                List<Long> candidates = List.copyOf(candidateIds);
                int k = cfg.getK();
                List<Long> primary = List.copyOf(topK(primaryRanked, k));
                ticket.task = new RecallExecution.Task<>(pool, deadline,
                        () -> { runShadow(ticket, candidates, scene, strategy, primary, k); return null; }, ticket::release);
                pool.execute(ticket.task);
                count("accepted");
            } catch (RejectedExecutionException rejected) {
                ticket.finish("saturated"); ticket.cancel();
            } catch (RuntimeException invalidInput) {
                // 可变集合/空元素等无效样本只影响影子；不把原输入和异常文本写入日志。
                ticket.finish("invalid_input"); ticket.cancel();
            }
        }
    }

    private void runShadow(Ticket ticket, List<Long> candidates, String scene,
                           String strategy, List<Long> primary, int k) {
        long started = System.nanoTime();
        try {
            List<RankedItem> ranked = rankRouter.rank(ticket.userId, candidates, scene, strategy);
            synchronized (ticket) {
                if (System.nanoTime() >= ticket.deadline) ticket.finish("timeout");
                if (ticket.finish("success")) {
                    meterRegistry.summary("recsys.rank.shadow.overlap", "shadow", strategy).record(overlapAtK(primary, topK(ranked, k)));
                    meterRegistry.counter("recsys.rank.shadow.runs", "shadow", strategy).increment();
                }
            }
        } catch (RuntimeException failure) {
            ticket.finish("failed"); log.debug("影子排序失败，已放弃本次比较 策略={}", strategy);
        } finally {
            meterRegistry.timer("recsys.rank.shadow.duration", "shadow", strategy).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /** 定期扫描上限为threads+queue；执行中取消不会虚假释放实际占用。 */
    private void expire() {
        long now = System.nanoTime();
        for (var ticket : inFlight.values()) if (now >= ticket.deadline) {
            synchronized (ticket) { if (ticket.finish("timeout")) ticket.cancel(); }
        }
    }

    private final class Ticket {
        private final long userId;
        private final long deadline;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile RecallExecution.Task<Void> task;
        private Ticket(long userId, long deadline) { this.userId = userId; this.deadline = deadline; }
        private boolean finish(String outcome) {
            if (!terminal.compareAndSet(false, true)) return false;
            count(outcome); return true;
        }
        private void cancel() { var current = task; if (current == null) release(); else current.cancel(true); }
        private void release() {
            // deadline在worker开始前已耗尽时，Task不会调用业务体，也要记录终态。
            finish(System.nanoTime() >= deadline ? "timeout" : "failed");
            if (released.compareAndSet(false, true)) { inFlight.remove(userId, this); slots.release(); }
        }
    }

    private void count(String outcome) { meterRegistry.counter("recsys.rank.shadow.tasks", "outcome", outcome).increment(); }

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

    /** 关闭先拒绝、取消所有票，再有界等待；不承诺强制停止原生模型。 */
    @PreDestroy
    void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        deadlines.shutdownNow();
        for (var ticket : inFlight.values()) synchronized (ticket) { ticket.finish("closed"); ticket.cancel(); }
        RecallExecution.stop(pool);
        try { deadlines.awaitTermination(100, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
