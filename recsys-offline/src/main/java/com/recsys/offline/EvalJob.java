package com.recsys.offline;

import com.recsys.common.rank.RankedItem;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.common.recall.RecallService;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import com.recsys.rank.RankRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 作业 eval:推荐质量离线评估闭环。
 *
 * <p><b>Ground truth(时间切分,标签无穿越)</b>:读 user_behavior 的 RATING 行,按全局
 * 时间分位点 splitTs(口径同 {@link GenSamplesJob})把每个用户的交互切成「历史」与「未来」:
 * <ul>
 *   <li>历史 = ts ≤ splitTs 的已评分物品(任意分值),用于从推荐结果里过滤「已知物品」;</li>
 *   <li>测试正例 = ts &gt; splitTs 且 value ≥ 4 且不在历史里的物品,即评估的命中目标。</li>
 * </ul>
 * 只评估「至少有 1 个测试正例」的用户。
 *
 * <p><b>候选生成</b>:复用线上同一套链路 —— {@link RecallService}(多路召回)→ 过滤历史 →
 * {@link RankRouter}(规则/ONNX 排序)→ 截断 topK。{@code --recall-only} 则逐条召回路单独按
 * recallScore 出 topK,用于横向对比各路召回的命中能力。
 *
 * <p><b>指标 @K</b>:Precision、Recall、HitRate、NDCG、MAP、MRR(准确性);
 * Coverage(覆盖度)、Diversity(类目多样性)、Novelty(热度新颖度,系统性)。
 *
 * <p><b>已知简化(诚实标注)</b>:Redis 的 CF 倒排 / pgvector 向量是用<strong>全量</strong>数据
 * (含测试期)离线构建的,故本评估存在轻度信息泄漏,离线指标偏乐观。严格版应仅用
 * ts ≤ splitTs 的数据重建全部离线存储后再评估;此处作为脚手架的可比基线。
 *
 * <p>参数:--k(默认 "10",可多档如 "10,20,50")、--valid-frac(默认 0.2)、
 * --max-users(默认 0=全部,&gt;0 抽样提速)、--recall-size(召回候选规模,默认 300)、
 * --rank-strategy(v1/onnx,默认走全局 recsys.rank.strategy)、--recall-only(逐路召回评估)、
 * --seed(默认 42)、--threads(并行评估线程数,默认 CPU 核数)。
 */
@Component
public class EvalJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(EvalJob.class);
    private static final String OUT_DIR = "eval";

    private final JdbcTemplate jdbc;
    private final RecallService recallService;
    private final RankRouter rankRouter;
    private final ContentService contentService;

    public EvalJob(JdbcTemplate jdbc, RecallService recallService,
                   RankRouter rankRouter, ContentService contentService) {
        this.jdbc = jdbc;
        this.recallService = recallService;
        this.rankRouter = rankRouter;
        this.contentService = contentService;
    }

    @Override
    public String name() {
        return "eval";
    }

    /** #2:行为读来源表(默认 user_behavior;run() 设、helper 读——离线作业单次运行、无并发)。 */
    private String bt = "user_behavior";

    @Override
    public void run(ApplicationArguments args) throws Exception {
        bt = BehaviorQuery.table(args);
        int[] ks = intListArg(args, "k", new int[]{10});
        int maxK = 0;
        for (int k : ks) {
            maxK = Math.max(maxK, k);
        }
        double validFrac = doubleArg(args, "valid-frac", 0.2);
        int maxUsers = intArg(args, "max-users", 0);
        int recallSize = intArg(args, "recall-size", 300);
        String rankStrategy = stringArg(args, "rank-strategy", null);
        boolean recallOnly = args.containsOption("recall-only");
        long seed = (long) doubleArg(args, "seed", 42);
        int threads = intArg(args, "threads", Runtime.getRuntime().availableProcessors());

        // 1. 读 RATING,拆历史/测试正例(全局时间分位点切分)
        Split split = buildSplit(validFrac);
        // 严格 eval 用:只打印切分点 ts(epoch 秒)供 run_strict_eval.sh 作为各召回作业的 --max-ts,然后退出。
        if (args.containsOption("print-split")) {
            System.out.println("SPLIT_TS=" + split.splitTs);
            log.info("print-split:valid-frac={} → splitTs={}(epoch 秒);用作 --max-ts 重建无泄漏存储",
                    validFrac, split.splitTs);
            return;
        }
        if (split.testUsers.isEmpty()) {
            log.warn("无测试正例;先跑 import-behavior(需有 ts>splitTs 的 RATING≥4)");
            return;
        }
        log.info("评估用户 {} 个(有测试正例),时间切分点 ts={},valid-frac={},并行线程={}",
                split.testUsers.size(), split.splitTs, validFrac, threads);

        // 2. 物品全集大小(coverage)+ 热度(novelty)+ 类目缓存(diversity)
        Catalog catalog = loadCatalog();
        log.info("物品全集 {} 个,总热度 {}", catalog.size, String.format("%.1f", catalog.totalPop));

        // 3. 抽样用户
        List<Long> users = new ArrayList<>(split.testUsers);
        if (maxUsers > 0 && users.size() > maxUsers) {
            Collections.shuffle(users, new Random(seed));
            users = users.subList(0, maxUsers);
            log.info("抽样 {} 个用户评估", users.size());
        }

        // 4. 逐用户生成推荐 → 累计指标
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outFile = Path.of(OUT_DIR, "metrics-" + ts + ".csv");
        Files.createDirectories(outFile.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            w.write("variant,k,users,precision,recall,ndcg,map,mrr,hitrate,coverage,diversity,novelty");
            w.newLine();

            if (recallOnly) {
                for (RecallChannel ch : RecallChannel.values()) {
                    Map<Integer, Acc> accs = evaluate(users, split, catalog, ks, maxK,
                            recallSize, rankStrategy, ch, threads);
                    report(w, "recall:" + ch, ks, accs, catalog.size);
                }
            } else {
                String variant = "pipeline:rank=" +
                        (rankStrategy == null ? "default" : rankStrategy);
                Map<Integer, Acc> accs = evaluate(users, split, catalog, ks, maxK,
                        recallSize, rankStrategy, null, threads);
                report(w, variant, ks, accs, catalog.size);
            }
        }
        log.info("eval 完成,指标已写入 {}", outFile.toAbsolutePath());
    }

    /**
     * 评估一种 variant。channel==null → 整链路(recall+rank);否则 → 单路召回(按 recallScore 排序)。
     *
     * <p>性能:重活(逐用户跑 recall→rank 全链路,~秒级/人)用线程池<strong>并行</strong>计算各用户的 topK,
     * 再单线程把结果 reduce 进指标累加器。并行只影响速度、不影响指标(顺序无关的累加 + 单线程 reduce)。
     * 链路本身无共享可变状态(JdbcTemplate 连接池、ONNX session 线程安全)。
     */
    private Map<Integer, Acc> evaluate(List<Long> users, Split split, Catalog catalog,
                                       int[] ks, int maxK, int recallSize,
                                       String rankStrategy, RecallChannel channel, int threads) {
        Map<Integer, Acc> accs = new LinkedHashMap<>();
        for (int k : ks) {
            accs.put(k, new Acc());
        }

        // 1. 并行算每个用户的 topK 推荐(无状态、可并行)
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        List<UserRecs> results = new ArrayList<>();
        try {
            List<Future<UserRecs>> futures = new ArrayList<>(users.size());
            for (long userId : users) {
                Set<Long> testPos = split.test.get(userId);
                if (testPos == null || testPos.isEmpty()) {
                    continue;
                }
                Set<Long> history = split.history.getOrDefault(userId, Set.of());
                futures.add(pool.submit(() -> {
                    List<Long> recs = recommend(userId, recallSize, maxK, rankStrategy, channel, history);
                    return recs.isEmpty() ? null : new UserRecs(recs, testPos);
                }));
            }
            for (Future<UserRecs> f : futures) {
                try {
                    UserRecs ur = f.get();
                    if (ur != null) {
                        results.add(ur);
                    }
                } catch (Exception e) {
                    log.debug("评估用户失败(忽略): {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdown();
        }

        // 2. 单线程 reduce 进指标累加器(顺序无关)
        for (UserRecs ur : results) {
            for (int k : ks) {
                accs.get(k).add(ur.recs, ur.testPos, k, catalog);
            }
        }
        log.info("variant {} 实评用户 {}",
                channel == null ? "pipeline" : "recall:" + channel, results.size());
        return accs;
    }

    /** 一个用户的 topK 推荐 + 其测试正例(并行计算产物,供单线程 reduce)。 */
    private record UserRecs(List<Long> recs, Set<Long> testPos) {
    }

    /** 生成一个用户的 topK 推荐(已过滤历史已知物品)。 */
    private List<Long> recommend(long userId, int recallSize, int maxK, String rankStrategy,
                                 RecallChannel channel, Set<Long> history) {
        List<RecallChannel> enabled = channel == null ? List.of() : List.of(channel);
        RecallContext ctx = new RecallContext(userId, recallSize, "eval", enabled, Map.of());
        List<RecallItem> recalled;
        try {
            recalled = recallService.recall(ctx);
        } catch (Exception e) {
            log.debug("召回失败 user={}: {}", userId, e.getMessage());
            return List.of();
        }
        if (recalled == null || recalled.isEmpty()) {
            return List.of();
        }
        if (channel != null) {
            // 单路召回:按 recallScore 降序取 topK,无排序层
            return recalled.stream()
                    .filter(it -> !history.contains(it.itemId()))
                    .sorted((a, b) -> Double.compare(b.recallScore(), a.recallScore()))
                    .map(RecallItem::itemId)
                    .limit(maxK)
                    .toList();
        }
        // 整链路:过滤历史 → 排序 → 截断
        List<Long> candidates = recalled.stream()
                .map(RecallItem::itemId)
                .filter(id -> !history.contains(id))
                .distinct()
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<RankedItem> ranked = rankRouter.rank(userId, candidates, "eval", rankStrategy);
        return ranked.stream().map(RankedItem::itemId).limit(maxK).toList();
    }

    // ---------- ground truth 构造 ----------

    private Split buildSplit(double validFrac) {
        List<long[]> ratings = new ArrayList<>();   // [user, item, ts, value*10]
        List<Long> allTs = new ArrayList<>();
        jdbc.query("SELECT user_id, item_id, value, extract(epoch from ts)::bigint AS ts " +
                        "FROM " + bt + " WHERE action='RATING'",
                rs -> {
                    long u = rs.getLong("user_id");
                    long it = rs.getLong("item_id");
                    long ts = rs.getLong("ts");
                    long v10 = Math.round(rs.getDouble("value") * 10);
                    ratings.add(new long[]{u, it, ts, v10});
                    allTs.add(ts);
                });
        Split split = new Split();
        if (allTs.isEmpty()) {
            return split;
        }
        allTs.sort(Long::compare);
        split.splitTs = allTs.get((int) ((1 - validFrac) * (allTs.size() - 1)));

        for (long[] r : ratings) {
            long u = r[0], item = r[1], ts = r[2];
            double value = r[3] / 10.0;
            if (ts <= split.splitTs) {
                split.history.computeIfAbsent(u, k -> new HashSet<>()).add(item);
            } else if (value >= 4.0) {
                // 测试正例须不在历史中(否则是已知物品,不计命中)
                split.futurePos.computeIfAbsent(u, k -> new ArrayList<>()).add(item);
            }
        }
        for (var e : split.futurePos.entrySet()) {
            Set<Long> history = split.history.getOrDefault(e.getKey(), Set.of());
            Set<Long> test = new HashSet<>();
            for (long item : e.getValue()) {
                if (!history.contains(item)) {
                    test.add(item);
                }
            }
            if (!test.isEmpty()) {
                split.test.put(e.getKey(), test);
                split.testUsers.add(e.getKey());
            }
        }
        return split;
    }

    private Catalog loadCatalog() {
        Catalog c = new Catalog();
        jdbc.query("SELECT item_id, GREATEST(popularity, 0) AS pop FROM item", rs -> {
            long id = rs.getLong("item_id");
            double pop = rs.getDouble("pop");
            c.popularity.put(id, pop);
            c.totalPop += pop;
        });
        c.size = c.popularity.size();
        return c;
    }

    private String categoryOf(long itemId, Map<Long, String> cache) {
        return cache.computeIfAbsent(itemId, id -> {
            Item it = contentService.findById(id);
            return it == null ? null : it.category();
        });
    }

    // ---------- 指标累加 ----------

    /** 单个 K 档的指标累加器。 */
    private final class Acc {
        double precision, recall, ndcg, ap, mrr, hitrate, diversity, novelty;
        int users;
        final Set<Long> covered = new HashSet<>();
        final Map<Long, String> catCache = new HashMap<>();

        void add(List<Long> recs, Set<Long> testPos, int k, Catalog catalog) {
            int n = Math.min(k, recs.size());
            int hits = 0;
            double dcg = 0, apSum = 0, firstHitRr = 0;
            for (int i = 0; i < n; i++) {
                long id = recs.get(i);
                covered.add(id);
                if (testPos.contains(id)) {
                    hits++;
                    dcg += 1.0 / log2(i + 2);
                    apSum += (double) hits / (i + 1);   // 命中位置处的 precision
                    if (firstHitRr == 0) {
                        firstHitRr = 1.0 / (i + 1);
                    }
                }
            }
            int ideal = Math.min(k, testPos.size());
            double idcg = 0;
            for (int i = 0; i < ideal; i++) {
                idcg += 1.0 / log2(i + 2);
            }
            precision += (double) hits / k;
            recall += (double) hits / testPos.size();
            ndcg += idcg == 0 ? 0 : dcg / idcg;
            ap += ideal == 0 ? 0 : apSum / ideal;
            mrr += firstHitRr;
            hitrate += hits > 0 ? 1 : 0;
            diversity += intraListDiversity(recs, n);
            novelty += listNovelty(recs, n, catalog);
            users++;
        }

        /** 列表内多样性:topN 中不同类目对占比(1=全异类,0=全同类)。 */
        private double intraListDiversity(List<Long> recs, int n) {
            if (n < 2) {
                return n == 1 ? 1.0 : 0.0;
            }
            long diff = 0, total = 0;
            for (int i = 0; i < n; i++) {
                String ci = categoryOf(recs.get(i), catCache);
                for (int j = i + 1; j < n; j++) {
                    total++;
                    String cj = categoryOf(recs.get(j), catCache);
                    if (ci == null || cj == null || !ci.equals(cj)) {
                        diff++;
                    }
                }
            }
            return total == 0 ? 0 : (double) diff / total;
        }

        /** 列表新颖度:topN 物品的平均 -log2(热度占比),越大越冷门/新颖。 */
        private double listNovelty(List<Long> recs, int n, Catalog catalog) {
            if (n == 0 || catalog.totalPop <= 0) {
                return 0;
            }
            double sum = 0;
            for (int i = 0; i < n; i++) {
                double pop = catalog.popularity.getOrDefault(recs.get(i), 0.0);
                double share = (pop + 1.0) / (catalog.totalPop + catalog.size);  // 拉普拉斯平滑
                sum += -log2(share);
            }
            return sum / n;
        }
    }

    private void report(BufferedWriter w, String variant, int[] ks,
                        Map<Integer, Acc> accs, int catalogSize) throws java.io.IOException {
        log.info("---- variant: {} ----", variant);
        log.info(String.format("%-4s %7s %8s %8s %8s %8s %8s %9s %9s %8s",
                "K", "Prec", "Recall", "NDCG", "MAP", "MRR", "HitRate", "Coverage", "Divers", "Novelty"));
        for (int k : ks) {
            Acc a = accs.get(k);
            int u = Math.max(a.users, 1);
            double precision = a.precision / u;
            double recall = a.recall / u;
            double ndcg = a.ndcg / u;
            double map = a.ap / u;
            double mrr = a.mrr / u;
            double hitrate = a.hitrate / u;
            double coverage = catalogSize == 0 ? 0 : (double) a.covered.size() / catalogSize;
            double diversity = a.diversity / u;
            double novelty = a.novelty / u;
            log.info(String.format("%-4d %7.4f %8.4f %8.4f %8.4f %8.4f %8.4f %9.4f %9.4f %8.3f",
                    k, precision, recall, ndcg, map, mrr, hitrate, coverage, diversity, novelty));
            w.write(String.format("%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
                    variant, k, a.users, precision, recall, ndcg, map, mrr, hitrate,
                    coverage, diversity, novelty));
            w.newLine();
        }
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    // ---------- 数据结构 ----------

    private static final class Split {
        long splitTs;
        final Map<Long, Set<Long>> history = new HashMap<>();      // user -> 历史已评分物品
        final Map<Long, List<Long>> futurePos = new HashMap<>();   // 中间态:未来高分(未去历史)
        final Map<Long, Set<Long>> test = new HashMap<>();         // user -> 测试正例
        final List<Long> testUsers = new ArrayList<>();
    }

    private static final class Catalog {
        int size;
        double totalPop;
        final Map<Long, Double> popularity = new HashMap<>();
    }

    // ---------- 参数解析 ----------

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0).trim()) : def;
    }

    private static double doubleArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0).trim()) : def;
    }

    private static String stringArg(ApplicationArguments a, String k, String def) {
        return a.containsOption(k) && !a.getOptionValues(k).isEmpty()
                ? a.getOptionValues(k).get(0).trim() : def;
    }

    private static int[] intListArg(ApplicationArguments a, String k, int[] def) {
        if (!a.containsOption(k) || a.getOptionValues(k).isEmpty()) {
            return def;
        }
        String[] parts = a.getOptionValues(k).get(0).split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }
}
