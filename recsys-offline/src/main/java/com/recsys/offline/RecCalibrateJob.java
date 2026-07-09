package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.rank.RankedItem;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.common.recall.RecallService;
import com.recsys.rank.RankRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 作业 rec-calibrate:拟合「推荐精排分数 → 真实正反馈率」的保序回归校准表,写 Redis {@code rec:calib:{model}};
 * 在线 {@code RecScoreCalibrator} 查表插值。校准让 rank 分成为可比概率,使 recall+rank 融合量纲一致、可解释
 * (isotonic 单调,不改单策略内排序,只改跨源可比性)。类比广告的 {@code ad-calibrate}。
 *
 * <p><b>数据(时间切分,标签无穿越,口径同 {@link EvalJob}/{@link GenSamplesJob})</b>:
 * <ul>
 *   <li>历史 = ts ≤ splitTs 的已评分物品(从候选里过滤);</li>
 *   <li>测试正例 = ts &gt; splitTs 且 value ≥ 4 且不在历史里 = 正样本(label=1),其余排序候选 label=0;</li>
 *   <li>对每个留出用户复用在线链路 recall(recallSize) → 过滤历史 → rank(strategy),收集 (rankScore, label)。</li>
 * </ul>
 * <b>拟合</b>:按 score 升序等频分箱 → 每箱经验正反馈率 → PAVA 强制单调递增 → x=箱内均分, y=校准率。
 *
 * <p>参数:--model(默认 rec,与在线 recsys.fusion.calibration.model 对齐)、--rank-strategy(默认走全局)、
 * --valid-frac(0.2)、--max-users(默认 1500,0=全部)、--recall-size(300)、--bins(20)、--seed(42)。
 */
@Component
public class RecCalibrateJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(RecCalibrateJob.class);

    private final JdbcTemplate jdbc;
    private final RecallService recallService;
    private final RankRouter rankRouter;
    private final StringRedisTemplate redis;

    public RecCalibrateJob(JdbcTemplate jdbc, RecallService recallService,
                           RankRouter rankRouter, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.recallService = recallService;
        this.rankRouter = rankRouter;
        this.redis = redis;
    }

    @Override
    public String name() {
        return "rec-calibrate";
    }

    @Override
    public void run(ApplicationArguments args) {
        String model = strArg(args, "model", "rec");
        String rankStrategy = strArg(args, "rank-strategy", null);
        double validFrac = doubleArg(args, "valid-frac", 0.2);
        int maxUsers = intArg(args, "max-users", 1500);
        int recallSize = intArg(args, "recall-size", 300);
        int bins = intArg(args, "bins", 20);
        long seed = intArg(args, "seed", 42);

        Split split = buildSplit(validFrac);
        if (split.testUsers.isEmpty()) {
            log.warn("无测试正例(需有 ts>splitTs 的 RATING≥4);先跑 import-behavior");
            return;
        }
        List<Long> users = new ArrayList<>(split.testUsers);
        java.util.Collections.shuffle(users, new Random(seed));
        if (maxUsers > 0 && users.size() > maxUsers) {
            users = users.subList(0, maxUsers);
        }
        log.info("rec-calibrate 开始:留出用户 {}(采样 {}), splitTs={}, strategy={}, recall-size={}",
                split.testUsers.size(), users.size(), split.splitTs,
                rankStrategy == null ? "default" : rankStrategy, recallSize);

        // 收集 (rankScore, label) 对
        List<double[]> pairs = new ArrayList<>();
        int done = 0;
        for (long userId : users) {
            Set<Long> history = split.history.getOrDefault(userId, Set.of());
            Set<Long> testPos = split.test.getOrDefault(userId, Set.of());
            collectPairs(userId, recallSize, rankStrategy, history, testPos, pairs);
            if (++done % 200 == 0) {
                log.info("  已处理 {} / {} 用户,累计样本 {}", done, users.size(), pairs.size());
            }
        }

        if (pairs.size() < bins * 5L) {
            log.warn("样本太少({} 条),放大 --max-users 或 --recall-size", pairs.size());
            return;
        }

        // 按 score 升序等频分箱 → 每箱 (均分, 经验正反馈率) → PAVA
        pairs.sort((a, b) -> Double.compare(a[0], b[0]));
        int n = pairs.size();
        double[] x = new double[bins];
        double[] y = new double[bins];
        double[] w = new double[bins];
        for (int b = 0; b < bins; b++) {
            int from = (int) ((long) b * n / bins);
            int to = (int) ((long) (b + 1) * n / bins);
            double sumS = 0, sumL = 0;
            for (int i = from; i < to; i++) {
                sumS += pairs.get(i)[0];
                sumL += pairs.get(i)[1];
            }
            int cnt = Math.max(1, to - from);
            x[b] = sumS / cnt;
            y[b] = sumL / cnt;
            w[b] = cnt;
        }
        pava(y, w);

        redis.opsForValue().set(RedisKeys.recCalib(model), toJson(x, y));
        log.info("rec-calibrate 完成 model={} 样本={} 分箱={};已写 {}。示例: score {}→{} … {}→{}",
                model, n, bins, RedisKeys.recCalib(model),
                round4(x[0]), round4(y[0]), round4(x[bins - 1]), round4(y[bins - 1]));
    }

    /** 一个用户:recall → 过滤历史 → rank,把每个排序候选的 (score, label) 收集起来。 */
    private void collectPairs(long userId, int recallSize, String rankStrategy,
                              Set<Long> history, Set<Long> testPos, List<double[]> out) {
        List<RecallItem> recalled;
        try {
            recalled = recallService.recall(new RecallContext(userId, recallSize, "eval", List.of(), Map.of()));
        } catch (Exception e) {
            log.debug("召回失败 user={}: {}", userId, e.getMessage());
            return;
        }
        if (recalled == null || recalled.isEmpty()) {
            return;
        }
        List<Long> candidates = recalled.stream()
                .map(RecallItem::itemId)
                .filter(id -> !history.contains(id))
                .distinct()
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        List<RankedItem> ranked;
        try {
            ranked = rankRouter.rank(userId, candidates, "eval", rankStrategy);
        } catch (Exception e) {
            log.debug("排序失败 user={}: {}", userId, e.getMessage());
            return;
        }
        for (RankedItem ri : ranked) {
            out.add(new double[]{ri.score(), testPos.contains(ri.itemId()) ? 1.0 : 0.0});
        }
    }

    // ---------- ground truth 时间切分(口径同 EvalJob）----------

    private Split buildSplit(double validFrac) {
        List<long[]> ratings = new ArrayList<>();   // [user, item, ts, value*10]
        List<Long> allTs = new ArrayList<>();
        jdbc.query("SELECT user_id, item_id, value, extract(epoch from ts)::bigint AS ts " +
                        "FROM user_behavior WHERE action='RATING'",
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

        Map<Long, List<Long>> futurePos = new HashMap<>();
        for (long[] r : ratings) {
            long u = r[0], item = r[1], ts = r[2];
            double value = r[3] / 10.0;
            if (ts <= split.splitTs) {
                split.history.computeIfAbsent(u, k -> new HashSet<>()).add(item);
            } else if (value >= 4.0) {
                futurePos.computeIfAbsent(u, k -> new ArrayList<>()).add(item);
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

    /** Pool Adjacent Violators:加权最小二乘下使 y 非递减(原地修改 y)。 */
    private static void pava(double[] y, double[] w) {
        int n = y.length;
        double[] val = new double[n];
        double[] wt = new double[n];
        int[] len = new int[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            val[k] = y[i];
            wt[k] = w[i];
            len[k] = 1;
            while (k > 0 && val[k - 1] > val[k]) {
                double mergedW = wt[k - 1] + wt[k];
                val[k - 1] = (val[k - 1] * wt[k - 1] + val[k] * wt[k]) / mergedW;
                wt[k - 1] = mergedW;
                len[k - 1] += len[k];
                k--;
            }
            k++;
        }
        int idx = 0;
        for (int b = 0; b < k; b++) {
            for (int j = 0; j < len[b]; j++) {
                y[idx++] = val[b];
            }
        }
    }

    private static String toJson(double[] x, double[] y) {
        StringJoiner xs = new StringJoiner(",", "[", "]");
        StringJoiner ys = new StringJoiner(",", "[", "]");
        for (int i = 0; i < x.length; i++) {
            xs.add(String.valueOf(round6(x[i])));
            ys.add(String.valueOf(round6(y[i])));
        }
        return "{\"x\":" + xs + ",\"y\":" + ys + "}";
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000) / 1_000_000.0;
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
