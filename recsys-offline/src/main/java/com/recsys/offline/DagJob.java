package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 作业 run-dag(E5):按<b>依赖 DAG 拓扑序</b>串行执行一组离线作业,<b>断一环即止</b>(不让下游用陈旧数据),
 * 配 Redis 分布式锁防并发重跑,并把每个作业的成功/失败 + 时间戳记到 Redis 供巡检。
 *
 * <p>替代"手动逐个 {@code --job=...}"的裸跑:40+ 作业彼此有依赖(如 user-embedding 依赖 backfill-embedding),
 * 手动漏跑/错序会让下游静默产出陈旧结果。本作业用声明式 {@link #DEPS} + {@link Dag} 拓扑规划保证顺序与完整性。
 *
 * <p>用法:
 * <pre>
 *   --job=run-dag --pipeline=full        # 预置管线(recall/ranking/full/quality)
 *   --job=run-dag --targets=hot,idf      # 指定目标(自动补齐传递依赖)
 *   --job=run-dag --pipeline=full --dry-run   # 只打印计划不执行
 * </pre>
 * 传给各子作业的是同一 {@link ApplicationArguments}(全局旗标如 --topn/--max-ts 一并生效)。
 */
@Component
public class DagJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(DagJob.class);
    private static final String LOCK_KEY = "job:dag:lock";
    private static final Duration LOCK_TTL = Duration.ofHours(6);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 作业 → 直接依赖(缺项=无依赖)。口径参照 CLAUDE.md 的引导顺序。 */
    private static final Map<String, List<String>> DEPS = Map.ofEntries(
            Map.entry("import-behavior", List.of("import-items")),
            Map.entry("backfill-embedding", List.of("import-items")),
            Map.entry("user-embedding", List.of("import-behavior", "backfill-embedding")),
            Map.entry("item-cf", List.of("import-behavior")),
            Map.entry("user-cf", List.of("import-behavior")),
            Map.entry("swing", List.of("import-behavior")),
            Map.entry("hot", List.of("import-behavior")),
            Map.entry("idf", List.of("import-items")),
            Map.entry("build-features", List.of("import-behavior")),
            Map.entry("gen-samples", List.of("build-features")),
            Map.entry("gen-samples-mt", List.of("import-behavior")),
            Map.entry("data-quality", List.of("backfill-embedding", "user-embedding", "hot")));

    /** 预置管线:目标作业集(传递依赖由 Dag 自动补齐)。 */
    private static final Map<String, List<String>> PIPELINES = Map.of(
            "recall", List.of("user-embedding", "item-cf", "user-cf", "swing", "hot", "idf"),
            "ranking", List.of("gen-samples", "gen-samples-mt"),
            "full", List.of("user-embedding", "item-cf", "user-cf", "swing", "hot", "idf",
                    "gen-samples", "gen-samples-mt", "data-quality"),
            "quality", List.of("data-quality"));

    private final ObjectProvider<List<OfflineJob>> jobsProvider;  // lazy:避免与自身循环依赖
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public DagJob(ObjectProvider<List<OfflineJob>> jobsProvider,
                  ObjectProvider<StringRedisTemplate> redisProvider) {
        this.jobsProvider = jobsProvider;
        this.redisProvider = redisProvider;
    }

    @Override
    public String name() {
        return "run-dag";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> targets = resolveTargets(args);
        if (targets.isEmpty()) {
            log.error("run-dag:未指定 --targets 且 --pipeline 无效;可选管线 {}", PIPELINES.keySet());
            return;
        }
        List<String> order;
        try {
            order = Dag.plan(targets, DEPS);
        } catch (IllegalStateException cycle) {
            log.error("run-dag 规划失败:{}", cycle.getMessage());
            return;
        }
        log.info("run-dag 计划({} 个作业,拓扑序):{}", order.size(), order);

        if (args.containsOption("dry-run")) {
            log.info("--dry-run:仅打印计划,不执行");
            return;
        }

        Map<String, OfflineJob> byName = jobsByName();
        List<String> missing = order.stream().filter(n -> !byName.containsKey(n)).toList();
        if (!missing.isEmpty()) {
            log.error("run-dag 终止:计划含未知作业 {}(拒绝半跑,以免下游用陈旧数据)", missing);
            return;
        }

        StringRedisTemplate redis = redisProvider.getIfAvailable();
        Lock lock = acquireLock(redis);
        if (lock == Lock.HELD) {
            log.warn("run-dag:已有 DAG 在跑(锁 {} 被占),本次跳过", LOCK_KEY);
            return;
        }
        try {
            execute(order, byName, redis, args);
        } finally {
            releaseLock(redis, lock == Lock.ACQUIRED);
        }
    }

    /** 锁获取结果:成功持有 / 被他人占用 / 无锁(Redis 缺失或异常,降级无锁运行)。 */
    private enum Lock { ACQUIRED, HELD, NONE }

    private void execute(List<String> order, Map<String, OfflineJob> byName,
                         StringRedisTemplate redis, ApplicationArguments args) {
        for (int i = 0; i < order.size(); i++) {
            String name = order.get(i);
            log.info("──[{}/{}] 执行作业 {} ─────────────", i + 1, order.size(), name);
            long t0 = System.currentTimeMillis();
            try {
                byName.get(name).run(args);
                recordStatus(redis, name, "ok", null);
                log.info("✅ {} 完成({} ms)", name, System.currentTimeMillis() - t0);
            } catch (Exception e) {
                recordStatus(redis, name, "fail", e.getMessage());
                List<String> skipped = order.subList(i + 1, order.size());
                log.error("❌ {} 失败:{};断链即止,跳过下游 {}", name, e.getMessage(), skipped, e);
                for (String s : skipped) {
                    recordStatus(redis, s, "skipped", "上游 " + name + " 失败");
                }
                return;  // fail-stop
            }
        }
        log.info("🎉 run-dag 全部完成:{}", order);
    }

    private List<String> resolveTargets(ApplicationArguments args) {
        if (args.containsOption("targets")) {
            String raw = args.getOptionValues("targets").get(0);
            List<String> out = new ArrayList<>();
            for (String t : raw.split(",")) {
                if (!t.isBlank()) {
                    out.add(t.trim());
                }
            }
            return out;
        }
        String pipeline = args.containsOption("pipeline") ? args.getOptionValues("pipeline").get(0).trim() : "full";
        return PIPELINES.getOrDefault(pipeline, List.of());
    }

    private Map<String, OfflineJob> jobsByName() {
        Map<String, OfflineJob> m = new HashMap<>();
        for (OfflineJob j : jobsProvider.getObject()) {
            if (!"run-dag".equals(j.name())) {   // 排除自身,避免递归
                m.put(j.name(), j);
            }
        }
        return m;
    }

    // ---- Redis 分布式锁 + 状态记录(Redis 不可用则降级:无锁运行 + 不记状态)----

    private Lock acquireLock(StringRedisTemplate redis) {
        if (redis == null) {
            return Lock.NONE;   // 无 Redis → 降级无锁运行
        }
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(LOCK_KEY, LocalDateTime.now().format(TS), LOCK_TTL);
            return Boolean.TRUE.equals(ok) ? Lock.ACQUIRED : Lock.HELD;
        } catch (Exception e) {
            log.warn("获取 DAG 锁失败,降级无锁运行: {}", e.getMessage());
            return Lock.NONE;   // Redis 异常 → 降级运行,而非误判为被占用
        }
    }

    private void releaseLock(StringRedisTemplate redis, boolean locked) {
        if (redis != null && locked) {
            try {
                redis.delete(LOCK_KEY);
            } catch (Exception ignore) {
                // 锁到期会自动释放
            }
        }
    }

    private void recordStatus(StringRedisTemplate redis, String job, String status, String detail) {
        if (redis == null) {
            return;
        }
        try {
            String v = status + "@" + LocalDateTime.now().format(TS) + (detail == null ? "" : (":" + detail));
            redis.opsForValue().set("job:status:" + job, v, Duration.ofDays(7));
        } catch (Exception ignore) {
            // 状态记录失败不影响作业本身
        }
    }
}
