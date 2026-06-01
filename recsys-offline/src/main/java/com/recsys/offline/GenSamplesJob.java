package com.recsys.offline;

import com.recsys.common.feature.FeatureService;
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
 *
 * <p><b>特征取数两种模式</b>(由 {@code --leaky} 切换):
 * <ul>
 *   <li><b>as-of(默认,无穿越)</b>:用 {@link AsOfFeatureBuilder} 按事件时间升序流式聚合,
 *       每条样本只取「截至其 ts(不含本次)」的特征 —— 等价 point-in-time join,杜绝数据穿越。
 *       不依赖 Redis feat:*,可直接在 import-behavior 之后跑。</li>
 *   <li><b>--leaky(对照)</b>:沿用旧逻辑,直接读 Redis feat:*(全量历史聚合,含目标交互本身),
 *       存在数据穿越、离线 AUC 偏乐观。保留它是为了 eval 横向对比「修穿越前后」的指标差异。
 *       此模式必须先跑 build-features。</li>
 * </ul>
 *
 * <p>参数:--neg-ratio(默认 2)、--valid-frac(默认 0.2)、--seed(默认 42)、--leaky(默认关=as-of)。
 */
@Component
public class GenSamplesJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(GenSamplesJob.class);
    private static final String OUT = "train/samples.csv";

    private final JdbcTemplate jdbc;
    private final FeatureService featureService;

    public GenSamplesJob(JdbcTemplate jdbc, FeatureService featureService) {
        this.jdbc = jdbc;
        this.featureService = featureService;
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
        boolean leaky = args.containsOption("leaky");
        Random rnd = new Random(seed);

        // 1. 全量评分事件(带类目),按 ts 升序 —— as-of 流式聚合与时间切分都依赖它
        List<Event> events = loadEvents();
        if (events.isEmpty()) {
            log.warn("无评分事件;先跑 import-behavior");
            return;
        }
        events.sort((a, b) -> Long.compare(a.ts, b.ts));

        // 每用户已评分集合(负采样排除)+ 归一化分母(全量最大计数,纯尺度因子)
        Map<Long, Set<Long>> ratedByUser = new HashMap<>();
        Map<Long, Long> userCnt = new HashMap<>();
        Map<Long, Long> itemCnt = new HashMap<>();
        List<Long> posTs = new ArrayList<>();
        for (Event e : events) {
            ratedByUser.computeIfAbsent(e.userId, k -> new HashSet<>()).add(e.itemId);
            userCnt.merge(e.userId, 1L, Long::sum);
            itemCnt.merge(e.itemId, 1L, Long::sum);
            if (e.value >= 4.0) {
                posTs.add(e.ts);
            }
        }
        if (posTs.isEmpty()) {
            log.warn("无正样本(RATING≥4)");
            return;
        }
        posTs.sort(Long::compare);
        long splitTs = posTs.get((int) ((1 - validFrac) * (posTs.size() - 1)));
        log.info("评分事件 {} 条,正样本 {} 条,时间切分点 ts={}(valid-frac={}),模式={}",
                events.size(), posTs.size(), splitTs, validFrac, leaky ? "leaky(全量,有穿越)" : "as-of(无穿越)");

        WeightedPool pool = loadItemPool();
        log.info("负采样物品池 {} 个,neg-ratio={}", pool.size(), negRatio);

        long maxUserCnt = userCnt.values().stream().mapToLong(Long::longValue).max().orElse(1);
        long maxItemCnt = itemCnt.values().stream().mapToLong(Long::longValue).max().orElse(1);
        AsOfFeatureBuilder asOf = new AsOfFeatureBuilder(
                Math.log1p(maxUserCnt), Math.log1p(maxItemCnt));

        // 全量类目表(负样本/leaky 模式取类目用;as-of 模式正样本类目随事件携带)
        Map<Long, String> catMap = loadCategoryMap();

        // leaky 模式的 Redis 特征缓存(仅 leaky 用)
        Map<Long, Map<String, Double>> userFeatCache = new HashMap<>();
        Map<Long, Map<String, Double>> itemFeatCache = new HashMap<>();

        Path out = Path.of(OUT);
        Files.createDirectories(out.getParent());
        long pos = 0, neg = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            // 表头:label, 稀疏原始列(供 DeepFM 做 embedding), <稠密特征顺序>, split
            // user_id/item_id/category 是给深度模型的类别型字段;稠密特征仍由 FeatureAssembler 装配。
            // LightGBM 训练侧(train_lgbm.py)会把这三列排除在特征之外,故两条训练路共用同一份 CSV。
            w.write("label,user_id,item_id,category," +
                    String.join(",", FeatureAssembler.FEATURE_ORDER) + ",split");
            w.newLine();

            // 按 ts 升序遍历:正样本在「apply 本次事件之前」snapshot,得到无穿越特征
            for (Event e : events) {
                if (e.value >= 4.0) {
                    String split = e.ts <= splitTs ? "train" : "valid";
                    Map<String, Double> uf = leaky
                            ? userFeatCache.computeIfAbsent(e.userId, featureService::userFeatures)
                            : asOf.snapshotUser(e.userId);
                    Map<String, Double> itf = leaky
                            ? itemFeatCache.computeIfAbsent(e.itemId, featureService::itemFeatures)
                            : asOf.snapshotItem(e.itemId);
                    writeAssembled(w, 1, e.userId, e.itemId, e.category, uf, itf, split);
                    pos++;

                    // 负样本:用「该正样本 ts 时点」的用户快照 + 负物品当下快照(as-of);leaky 则读 Redis
                    Set<Long> rated = ratedByUser.getOrDefault(e.userId, Set.of());
                    int got = 0, tries = 0;
                    while (got < negRatio && tries < negRatio * 20) {
                        tries++;
                        long negItem = pool.sample(rnd);
                        if (rated.contains(negItem)) {
                            continue;
                        }
                        String negSplit = rnd.nextDouble() < validFrac ? "valid" : "train";
                        Map<String, Double> nItf = leaky
                                ? itemFeatCache.computeIfAbsent(negItem, featureService::itemFeatures)
                                : asOf.snapshotItem(negItem);
                        writeAssembled(w, 0, e.userId, negItem, catMap.get(negItem), uf, nItf, negSplit);
                        got++;
                        neg++;
                    }
                }
                // 无论正负,事件都并入 as-of 聚合(在快照之后,保证「不含本次」)
                asOf.apply(e.userId, e.itemId, e.value, e.category);
            }
        }
        log.info("gen-samples 完成({}):正 {} + 负 {} = {} 行 → {}",
                leaky ? "leaky" : "as-of", pos, neg, pos + neg, out.toAbsolutePath());
    }

    /** 用已取好的 user/item 特征装配并写一行(as-of 与 leaky 共用,只是特征来源不同)。 */
    private void writeAssembled(BufferedWriter w, int label, long userId, long itemId, String category,
                                Map<String, Double> userFeat, Map<String, Double> itemFeat,
                                String split) throws java.io.IOException {
        double[] f = FeatureAssembler.assemble(userFeat, itemFeat, category);
        StringBuilder sb = new StringBuilder();
        sb.append(label);
        // 稀疏原始列:user_id, item_id, category(深度模型 embedding 输入;类目为空写空串)
        sb.append(',').append(userId)
                .append(',').append(itemId)
                .append(',').append(category == null ? "" : category);
        for (double v : f) {
            sb.append(',').append(v);
        }
        sb.append(',').append(split);
        w.write(sb.toString());
        w.newLine();
    }

    /** 全量评分事件(join 类目),供 as-of 流式聚合与时间切分。 */
    private List<Event> loadEvents() {
        List<Event> events = new ArrayList<>();
        jdbc.query("SELECT b.user_id, b.item_id, b.value, " +
                        "extract(epoch from b.ts)::bigint AS ts, i.category " +
                        "FROM user_behavior b LEFT JOIN item i ON b.item_id = i.item_id " +
                        "WHERE b.action='RATING'",
                rs -> {
                    events.add(new Event(
                            rs.getLong("user_id"), rs.getLong("item_id"),
                            rs.getDouble("value"), rs.getLong("ts"), rs.getString("category")));
                });
        return events;
    }

    /** 物品全集类目映射(负样本取类目用)。 */
    private Map<Long, String> loadCategoryMap() {
        Map<Long, String> map = new HashMap<>();
        jdbc.query("SELECT item_id, category FROM item",
                rs -> {
                    map.put(rs.getLong("item_id"), rs.getString("category"));
                });
        return map;
    }

    /** 一条评分事件。 */
    private record Event(long userId, long itemId, double value, long ts, String category) {
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
