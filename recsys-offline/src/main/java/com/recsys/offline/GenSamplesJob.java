package com.recsys.offline;

import com.recsys.common.feature.FeatureService;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import com.recsys.rank.FeatureAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 作业 gen-samples:构造 CTR 训练样本 CSV(label, 特征..., split)。
 *
 * <p>样本定义:
 * <ul>
 *   <li>正样本(label=1):RATING 且 value≥4 的 (user,item);</li>
 *   <li>负样本(label=0):每个正样本配 neg-ratio 个该用户「未评分」物品,
 *       按热度加权采样以近似「曝光未点击」(MovieLens 无真实曝光日志,只能采样);</li>
 *   <li>时间切分:正样本按 ts 取分位点,早 train / 晚 valid;负样本按比例随机分。</li>
 * </ul>
 *
 * <p>特征经共享 {@link FeatureAssembler} 装配,与在线 OnnxRankService 完全一致。
 * 因此本作业必须在 build-features 之后运行(读 Redis feat:*)。
 *
 * <p>已知简化:特征(用户/物品均分、类目偏好)用全量历史计算,含目标交互本身,
 * 存在轻度数据穿越,会让离线 AUC 偏乐观;生产应按事件时间 as-of 计算特征。
 *
 * <p>参数:--neg-ratio(默认 2)、--valid-frac(默认 0.2)、--seed(默认 42)。
 */
@Component
public class GenSamplesJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(GenSamplesJob.class);
    private static final String OUT = "train/samples.csv";

    private final JdbcTemplate jdbc;
    private final FeatureService featureService;
    private final ContentService contentService;

    public GenSamplesJob(JdbcTemplate jdbc, FeatureService featureService, ContentService contentService) {
        this.jdbc = jdbc;
        this.featureService = featureService;
        this.contentService = contentService;
    }

    @Override
    public String name() {
        return "gen-samples";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int negRatio = intArg(args, "neg-ratio", 2);
        double validFrac = doubleArg(args, "valid-frac", 0.2);
        long seed = (long) doubleArg(args, "seed", 42);
        Random rnd = new Random(seed);

        // 1. 正样本 + 每用户已评分集合
        List<long[]> positives = new ArrayList<>();          // [user, item, ts]
        Map<Long, Set<Long>> ratedByUser = new HashMap<>();
        List<Long> posTs = new ArrayList<>();
        jdbc.query("SELECT user_id, item_id, value, extract(epoch from ts)::bigint AS ts " +
                        "FROM user_behavior WHERE action='RATING'",
                rs -> {
                    long u = rs.getLong("user_id");
                    long it = rs.getLong("item_id");
                    double v = rs.getDouble("value");
                    long ts = rs.getLong("ts");
                    ratedByUser.computeIfAbsent(u, k -> new HashSet<>()).add(it);
                    if (v >= 4.0) {
                        positives.add(new long[]{u, it, ts});
                        posTs.add(ts);
                    }
                });
        if (positives.isEmpty()) {
            log.warn("无正样本;先跑 import-behavior + build-features");
            return;
        }
        // 时间分位点:早于 splitTs 进 train,晚于进 valid
        posTs.sort(Long::compare);
        long splitTs = posTs.get((int) ((1 - validFrac) * (posTs.size() - 1)));
        log.info("正样本 {} 条,时间切分点 ts={}(valid-frac={})", positives.size(), splitTs, validFrac);

        // 2. 负采样物品池(按热度加权)
        WeightedPool pool = loadItemPool();
        log.info("负采样物品池 {} 个,neg-ratio={}", pool.size(), negRatio);

        // 3. 特征装配缓存(按 user/item 复用,避免重复读 Redis)
        Map<Long, Map<String, Double>> userFeatCache = new HashMap<>();
        Map<Long, Map<String, Double>> itemFeatCache = new HashMap<>();
        Map<Long, String> catCache = new HashMap<>();

        Path out = Path.of(OUT);
        Files.createDirectories(out.getParent());
        long pos = 0, neg = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            // 表头:label, <特征顺序>, split
            w.write("label," + String.join(",", FeatureAssembler.FEATURE_ORDER) + ",split");
            w.newLine();

            for (long[] p : positives) {
                long u = p[0], item = p[1], ts = p[2];
                String split = ts <= splitTs ? "train" : "valid";
                writeRow(w, 1, u, item, split, userFeatCache, itemFeatCache, catCache);
                pos++;

                Set<Long> rated = ratedByUser.getOrDefault(u, Set.of());
                int got = 0, tries = 0;
                while (got < negRatio && tries < negRatio * 20) {
                    tries++;
                    long negItem = pool.sample(rnd);
                    if (rated.contains(negItem)) {
                        continue;
                    }
                    String negSplit = rnd.nextDouble() < validFrac ? "valid" : "train";
                    writeRow(w, 0, u, negItem, negSplit, userFeatCache, itemFeatCache, catCache);
                    got++;
                    neg++;
                }
            }
        }
        log.info("gen-samples 完成:正 {} + 负 {} = {} 行 → {}", pos, neg, pos + neg,
                out.toAbsolutePath());
    }

    private void writeRow(BufferedWriter w, int label, long userId, long itemId, String split,
                          Map<Long, Map<String, Double>> userFeatCache,
                          Map<Long, Map<String, Double>> itemFeatCache,
                          Map<Long, String> catCache) throws java.io.IOException {
        Map<String, Double> uf = userFeatCache.computeIfAbsent(userId, featureService::userFeatures);
        Map<String, Double> itf = itemFeatCache.computeIfAbsent(itemId, featureService::itemFeatures);
        String cat = catCache.computeIfAbsent(itemId, id -> {
            Item it = contentService.findById(id);
            return it == null ? null : it.category();
        });
        double[] f = FeatureAssembler.assemble(uf, itf, cat);
        StringBuilder sb = new StringBuilder();
        sb.append(label);
        for (double v : f) {
            sb.append(',').append(v);
        }
        sb.append(',').append(split);
        w.write(sb.toString());
        w.newLine();
    }

    private WeightedPool loadItemPool() {
        List<Long> ids = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        jdbc.query("SELECT item_id, GREATEST(popularity, 1) AS pop FROM item", rs -> {
            ids.add(rs.getLong("item_id"));
            w.add(rs.getDouble("pop"));
        });
        return new WeightedPool(ids, w);
    }

    /** 按权重的前缀和采样池。 */
    private static final class WeightedPool {
        private final long[] ids;
        private final double[] cum;
        private final double total;

        WeightedPool(List<Long> idList, List<Double> weights) {
            ids = new long[idList.size()];
            cum = new double[idList.size()];
            double acc = 0;
            for (int i = 0; i < idList.size(); i++) {
                ids[i] = idList.get(i);
                acc += weights.get(i);
                cum[i] = acc;
            }
            total = acc;
        }

        int size() {
            return ids.length;
        }

        long sample(Random rnd) {
            double r = rnd.nextDouble() * total;
            int lo = 0, hi = cum.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cum[mid] < r) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return ids[lo];
        }
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static double doubleArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }
}
