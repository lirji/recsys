package com.recsys.offline;

import com.recsys.common.rank.RankedItem;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.common.recall.RecallService;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 作业 tune-fusion:多目标融合权重(cvrBias / cvrWeight)离线网格搜参。
 *
 * <p><b>背景</b>:mmoe/din 的最终分 {@code score = pCTR·(cvrBias + cvrWeight·pCVR)},其中
 * cvrBias/cvrWeight 原为拍脑袋常量(recsys.rank.multi-task)。本作业在时间切分留出集上,
 * 用 NDCG@K 为目标网格搜出更优权重,把"拍脑袋"改成"数据驱动"。
 *
 * <p><b>高效关键</b>:{@code MmoeRankService}/{@code DinRankService} 已把原始 {@code pCTR}/{@code pCVR}
 * 写进 {@link RankedItem#featureSnapshot()}。故只需<strong>重跑一次</strong> recall→rank 拿到每个候选的
 * (pCTR, pCVR, 是否测试正例),之后对每组 (cvrBias, cvrWeight) 只做<strong>纯内存重打分 + 排序 + 评估</strong>,
 * 无需为每组权重重推模型 —— 一次昂贵 rank,几十组权重秒级评完。
 *
 * <p><b>口径</b>:时间切分同 {@link EvalJob}(历史 ts≤splitTs,测试正例 ts&gt;splitTs 且 value≥4 且不在历史)。
 *
 * <p>参数:--strategy(默认 mmoe,须为多目标策略 mmoe/din)、--k(默认 20)、--valid-frac(0.2)、
 * --max-users(默认 1500,0=全部)、--recall-size(300)、--seed(42)、
 * --biases(默认 "0,0.05,0.1,0.2,0.5,1.0")、--weights(默认 "0,0.5,1,1.5,2,3")。
 */
@Component
public class TuneFusionJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(TuneFusionJob.class);
    private static final String OUT_DIR = "eval";
    // 与 RankProperties.MultiTask 默认值对齐,便于报告里标注"当前默认"作对照
    private static final double CUR_BIAS = 0.1;
    private static final double CUR_WEIGHT = 1.0;

    private final JdbcTemplate jdbc;
    private final RecallService recallService;
    private final RankRouter rankRouter;

    public TuneFusionJob(JdbcTemplate jdbc, RecallService recallService, RankRouter rankRouter) {
        this.jdbc = jdbc;
        this.recallService = recallService;
        this.rankRouter = rankRouter;
    }

    @Override
    public String name() {
        return "tune-fusion";
    }

    /** #2:行为读来源表(默认 user_behavior;run() 设、helper 读——离线作业单次运行、无并发)。 */
    private String bt = "user_behavior";

    @Override
    public void run(ApplicationArguments args) throws Exception {
        bt = BehaviorQuery.table(args);
        String strategy = strArg(args, "strategy", "mmoe");
        int k = intArg(args, "k", 20);
        double validFrac = doubleArg(args, "valid-frac", 0.2);
        int maxUsers = intArg(args, "max-users", 1500);
        int recallSize = intArg(args, "recall-size", 300);
        long seed = intArg(args, "seed", 42);
        double[] biases = parseGrid(strArg(args, "biases", "0,0.05,0.1,0.2,0.5,1.0"));
        double[] weights = parseGrid(strArg(args, "weights", "0,0.5,1,1.5,2,3"));

        if (!strategy.equals("mmoe") && !strategy.equals("din")) {
            log.warn("tune-fusion 只对多目标策略有意义(mmoe/din);当前 --strategy={} 非多目标,退出", strategy);
            return;
        }

        Split split = buildSplit(validFrac);
        if (split.testUsers.isEmpty()) {
            log.warn("无测试正例(需 ts>splitTs 的 RATING≥4);先跑 import-behavior");
            return;
        }
        List<Long> users = new ArrayList<>(split.testUsers);
        java.util.Collections.shuffle(users, new Random(seed));
        if (maxUsers > 0 && users.size() > maxUsers) {
            users = users.subList(0, maxUsers);
        }
        log.info("tune-fusion 开始:strategy={} 留出用户 {}(采样 {}) splitTs={} k={}",
                strategy, split.testUsers.size(), users.size(), split.splitTs, k);

        // 一次昂贵重跑:收集每个用户候选的 (pCTR, pCVR, 是否正例)
        List<UserHeads> data = new ArrayList<>();
        int missSnap = 0, done = 0;
        for (long userId : users) {
            Set<Long> history = split.history.getOrDefault(userId, Set.of());
            Set<Long> testPos = split.test.getOrDefault(userId, Set.of());
            UserHeads uh = collect(userId, recallSize, strategy, history, testPos);
            if (uh == null) {
                continue;
            }
            if (uh.missingHeads) {
                missSnap++;
            }
            data.add(uh);
            if (++done % 200 == 0) {
                log.info("  已重跑 {} / {} 用户", done, users.size());
            }
        }
        if (data.isEmpty()) {
            log.warn("无有效用户数据;检查召回/排序是否正常");
            return;
        }
        if (missSnap == data.size()) {
            log.warn("全部用户都缺 pCTR/pCVR 快照 —— 该策略未真正跑多目标模型。请用 RANK_STRATEGY={} 启动 offline"
                    + "(否则 {} 的条件 Bean 不存在,RankRouter 回退规则排序、无 pCTR/pCVR)。", strategy, strategy);
            return;
        } else if (missSnap > 0) {
            log.warn("有 {} 个用户的排序结果缺 pCTR/pCVR 快照(部分召回/排序为空,忽略)", missSnap);
        }

        // 网格搜参:纯内存重打分 + NDCG@K
        double bestNdcg = -1, bestBias = CUR_BIAS, bestWeight = CUR_WEIGHT;
        List<double[]> grid = new ArrayList<>();   // [bias, weight, ndcg]
        for (double bias : biases) {
            for (double weight : weights) {
                double ndcg = evalNdcg(data, bias, weight, k);
                grid.add(new double[]{bias, weight, ndcg});
                if (ndcg > bestNdcg) {
                    bestNdcg = ndcg;
                    bestBias = bias;
                    bestWeight = weight;
                }
            }
        }
        double curNdcg = evalNdcg(data, CUR_BIAS, CUR_WEIGHT, k);

        // 报告
        grid.sort((a, b) -> Double.compare(b[2], a[2]));
        log.info("tune-fusion 结果(strategy={} 用户={} k={}):", strategy, data.size(), k);
        log.info("  当前默认 cvrBias={}, cvrWeight={} → NDCG@{}={}", CUR_BIAS, CUR_WEIGHT, k, round4(curNdcg));
        log.info("  最优     cvrBias={}, cvrWeight={} → NDCG@{}={} (相对当前 {}{}%)",
                bestBias, bestWeight, k, round4(bestNdcg),
                bestNdcg >= curNdcg ? "+" : "", round2(100 * (bestNdcg - curNdcg) / Math.max(1e-9, curNdcg)));
        log.info("  Top5 组合:");
        for (int i = 0; i < Math.min(5, grid.size()); i++) {
            double[] g = grid.get(i);
            log.info("    cvrBias={} cvrWeight={} → NDCG@{}={}", g[0], g[1], k, round4(g[2]));
        }
        log.info("  → 建议把 recsys.rank.multi-task 设为 cvr-bias={}, cvr-weight={}(无需重训,重启生效)",
                bestBias, bestWeight);

        writeCsv(strategy, k, grid, curNdcg);
    }

    /** 一个用户:recall → 过滤历史 → rank,收集候选的 (pCTR, pCVR, 正例标签)。 */
    private UserHeads collect(long userId, int recallSize, String strategy,
                              Set<Long> history, Set<Long> testPos) {
        List<RecallItem> recalled;
        try {
            recalled = recallService.recall(new RecallContext(userId, recallSize, "eval", List.of(), Map.of()));
        } catch (Exception e) {
            return null;
        }
        if (recalled == null || recalled.isEmpty()) {
            return null;
        }
        List<Long> candidates = recalled.stream()
                .map(RecallItem::itemId)
                .filter(id -> !history.contains(id))
                .distinct()
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        List<RankedItem> ranked;
        try {
            ranked = rankRouter.rank(userId, candidates, "eval", strategy);
        } catch (Exception e) {
            return null;
        }
        UserHeads uh = new UserHeads();
        uh.numPos = testPos.size();
        for (RankedItem ri : ranked) {
            Map<String, Double> snap = ri.featureSnapshot();
            Double pctr = snap == null ? null : snap.get("pCTR");
            Double pcvr = snap == null ? null : snap.get("pCVR");
            if (pctr == null || pcvr == null) {
                uh.missingHeads = true;
                continue;
            }
            uh.pctr.add(pctr);
            uh.pcvr.add(pcvr);
            uh.pos.add(testPos.contains(ri.itemId()));
        }
        return uh.pctr.isEmpty() ? null : uh;
    }

    /** 用给定 (bias,weight) 对每个用户重打分排序取 topK,算平均 NDCG@K。 */
    private double evalNdcg(List<UserHeads> data, double bias, double weight, int k) {
        double sum = 0;
        int users = 0;
        for (UserHeads uh : data) {
            int m = uh.pctr.size();
            Integer[] idx = new Integer[m];
            double[] score = new double[m];
            for (int i = 0; i < m; i++) {
                idx[i] = i;
                score[i] = uh.pctr.get(i) * (bias + weight * uh.pcvr.get(i));
            }
            final double[] s = score;
            java.util.Arrays.sort(idx, (a, b) -> Double.compare(s[b], s[a]));
            double dcg = 0;
            for (int rank = 0; rank < Math.min(k, m); rank++) {
                if (uh.pos.get(idx[rank])) {
                    dcg += 1.0 / (Math.log(rank + 2) / Math.log(2));   // rel∈{0,1}
                }
            }
            int ideal = Math.min(k, uh.numPos);
            if (ideal <= 0) {
                continue;
            }
            double idcg = 0;
            for (int rank = 0; rank < ideal; rank++) {
                idcg += 1.0 / (Math.log(rank + 2) / Math.log(2));
            }
            sum += dcg / idcg;
            users++;
        }
        return users == 0 ? 0 : sum / users;
    }

    private void writeCsv(String strategy, int k, List<double[]> grid, double curNdcg) throws Exception {
        Files.createDirectories(Path.of(OUT_DIR));
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path out = Path.of(OUT_DIR, "tune-fusion-" + ts + ".csv");
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("strategy,cvr_bias,cvr_weight,ndcg@" + k + ",is_current\n");
            for (double[] g : grid) {
                boolean cur = g[0] == CUR_BIAS && g[1] == CUR_WEIGHT;
                w.write(strategy + "," + g[0] + "," + g[1] + "," + round4(g[2]) + "," + (cur ? "1" : "0") + "\n");
            }
        }
        log.info("网格结果已写 {}(当前默认 NDCG@{}={})", out, k, round4(curNdcg));
    }

    // ---------- ground truth 时间切分(口径同 EvalJob）----------

    private Split buildSplit(double validFrac) {
        List<long[]> ratings = new ArrayList<>();
        List<Long> allTs = new ArrayList<>();
        jdbc.query("SELECT user_id, item_id, value, extract(epoch from ts)::bigint AS ts " +
                        "FROM " + bt + " WHERE action='RATING'",
                rs -> {
                    ratings.add(new long[]{rs.getLong("user_id"), rs.getLong("item_id"),
                            rs.getLong("ts"), Math.round(rs.getDouble("value") * 10)});
                    allTs.add(rs.getLong("ts"));
                });
        Split split = new Split();
        if (allTs.isEmpty()) {
            return split;
        }
        allTs.sort(Long::compare);
        split.splitTs = allTs.get((int) ((1 - validFrac) * (allTs.size() - 1)));
        Map<Long, List<Long>> futurePos = new HashMap<>();
        for (long[] r : ratings) {
            long u = r[0], item = r[1], ts = r[2];
            double value = r[3] / 10.0;
            if (ts <= split.splitTs) {
                split.history.computeIfAbsent(u, x -> new HashSet<>()).add(item);
            } else if (value >= 4.0) {
                futurePos.computeIfAbsent(u, x -> new ArrayList<>()).add(item);
            }
        }
        for (var e : futurePos.entrySet()) {
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

    private static final class Split {
        long splitTs;
        final Map<Long, Set<Long>> history = new HashMap<>();
        final Map<Long, Set<Long>> test = new HashMap<>();
        final List<Long> testUsers = new ArrayList<>();
    }

    /** 一个用户的候选头快照 + 正例信息(供网格搜参内存重打分)。 */
    private static final class UserHeads {
        final List<Double> pctr = new ArrayList<>();
        final List<Double> pcvr = new ArrayList<>();
        final List<Boolean> pos = new ArrayList<>();
        int numPos;
        boolean missingHeads;
    }

    private static double[] parseGrid(String csv) {
        String[] parts = csv.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Double.parseDouble(parts[i].trim());
        }
        return out;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static double doubleArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }

    private static String strArg(ApplicationArguments a, String k, String def) {
        return a.containsOption(k) ? a.getOptionValues(k).get(0) : def;
    }
}
