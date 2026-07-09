package com.recsys.offline;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 作业 gen-samples-mt:为<b>多目标排序(MMoE/ESMM)</b>与<b>行为序列建模(DIN)</b>构造样本。
 *
 * <p>独立产出 {@code train/samples_mt.csv},<b>不复用也不影响</b> {@code samples.csv}
 * (后者喂 LightGBM/DeepFM,且 train_lgbm.py 是「除黑名单外全当特征」,加列会污染它)。
 *
 * <p><b>多目标标签(ESMM 的 CTR/CVR 结构)</b>:
 * <ul>
 *   <li>负采样物品(未交互)→ {@code label_click=0, label_like=0};</li>
 *   <li>评分 &lt;4 的交互     → {@code label_click=1, label_like=0};</li>
 *   <li>评分 ≥4 的交互     → {@code label_click=1, label_like=1}。</li>
 * </ul>
 * click=「是否交互」、like=「交互后是否满意」。like 只在 click=1 上有定义,故训练侧用
 * pCTCVR = pCTR·pCVR 在全样本上学习,天然解决 CVR 的样本选择偏差。
 * <b>注意</b>:与 gen-samples 只留 ≥4 正例不同,这里把<strong>每条评分事件</strong>都作为 click 正例。
 *
 * <p><b>行为序列(point-in-time,无穿越)</b>:按 ts 升序流式遍历,为每条样本快照该用户
 * 「截至本次事件之前」的近 {@code seq-len}(默认 20)个 <b>≥4 正反馈</b>物品(最近的在最后)。
 * 序列在 apply 本次事件<em>之前</em>取,故不含目标交互本身。负样本与其所属正样本共享同一用户序列
 * (序列刻画用户而非候选物品)。
 *
 * <p>稠密特征仍走共享 {@link FeatureAssembler} + as-of {@link AsOfFeatureBuilder},与在线一致。
 *
 * <p>表头:{@code label_click,label_like,user_id,item_id,category,<5 稠密>,seq_items,seq_cats,seq_len,position,split}。
 * {@code seq_items}/{@code seq_cats} 用 {@code |} 拼接(类目为单值,无管道符,已核对)。
 *
 * <p><b>位置偏差去偏(PAL,docs/04 §13)</b>:多出一列 {@code position}(展示位次),供 train_mmoe/train_din
 * 的 position-bias 塔在训练时"解释掉"位置效应、导出时丢弃(线上推理契约不变)。本仓的历史样本由评分
 * 派生、<b>无真实曝光位次</b>,故 {@code position} 默认恒 0(=未知,PAL 塔对其不施偏置 → 休眠不影响精度);
 * 真实系统应从曝光日志取位次。{@code --position-proxy} 打开后用<b>热度名次</b>作教学代理(热门≈靠前),
 * 让 PAL 机制可被观察(代理与热度特征有相关,仅教学用)。
 *
 * <p>参数:--neg-ratio(默认 2)、--valid-frac(默认 0.2)、--seed(默认 42)、--seq-len(默认 20)、
 * --position-proxy(默认 false)、--max-position(默认 10)。
 */
@Component
public class GenSamplesMtJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(GenSamplesMtJob.class);
    private static final String OUT = "train/samples_mt.csv";
    private static final int DEFAULT_SEQ_LEN = 20;
    private static final char SEQ_DELIM = '|';

    private final JdbcTemplate jdbc;

    public GenSamplesMtJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "gen-samples-mt";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int negRatio = intArg(args, "neg-ratio", 2);
        double hardNegRatio = doubleArg(args, "hard-neg-ratio", 0.0);  // S6:同类目 hard negative 占比(0=纯热度随机)
        double validFrac = doubleArg(args, "valid-frac", 0.2);
        long seed = (long) doubleArg(args, "seed", 42);
        int seqLen = intArg(args, "seq-len", DEFAULT_SEQ_LEN);
        boolean positionProxy = args.containsOption("position-proxy");
        int maxPosition = intArg(args, "max-position", 10);
        Random rnd = new Random(seed);

        List<Event> events = loadEvents();
        if (events.isEmpty()) {
            log.warn("无评分事件;先跑 import-behavior");
            return;
        }
        events.sort((a, b) -> Long.compare(a.ts, b.ts));

        Map<Long, Set<Long>> ratedByUser = new HashMap<>();
        Map<Long, Long> userCnt = new HashMap<>();
        Map<Long, Long> itemCnt = new HashMap<>();
        List<Long> allTs = new ArrayList<>(events.size());
        for (Event e : events) {
            ratedByUser.computeIfAbsent(e.userId, k -> new HashSet<>()).add(e.itemId);
            userCnt.merge(e.userId, 1L, Long::sum);
            itemCnt.merge(e.itemId, 1L, Long::sum);
            allTs.add(e.ts);
        }
        // 时间切分点:用全部交互 ts(click 正例覆盖所有评分,口径与 gen-samples 的正例分位点同源)
        allTs.sort(Long::compare);
        long splitTs = allTs.get((int) ((1 - validFrac) * (allTs.size() - 1)));
        log.info("评分事件 {} 条,时间切分点 ts={}(valid-frac={}),seq-len={}", events.size(), splitTs, validFrac, seqLen);

        WeightedPool pool = loadItemPool();
        log.info("负采样物品池 {} 个,neg-ratio={}", pool.size(), negRatio);

        long maxUserCnt = userCnt.values().stream().mapToLong(Long::longValue).max().orElse(1);
        long maxItemCnt = itemCnt.values().stream().mapToLong(Long::longValue).max().orElse(1);
        AsOfFeatureBuilder asOf = new AsOfFeatureBuilder(Math.log1p(maxUserCnt), Math.log1p(maxItemCnt));
        Map<Long, String> catMap = loadCategoryMap();
        // S6 hard negative:类目→物品倒排,用于采「同类目、更难区分」的负例(比纯热度随机更有信息量,
        // 让模型学到细粒度偏好而非只会区分冷热)。仅 hard-neg-ratio>0 时构建。
        Map<String, List<Long>> itemsByCat = new HashMap<>();
        if (hardNegRatio > 0) {
            for (var en : catMap.entrySet()) {
                if (en.getValue() != null) {
                    itemsByCat.computeIfAbsent(en.getValue(), k -> new ArrayList<>()).add(en.getKey());
                }
            }
            log.info("hard negative:hard-neg-ratio={},类目倒排 {} 个类目", hardNegRatio, itemsByCat.size());
        }

        // 位置代理(可选):按热度名次给展示位次(热门≈位次靠前);默认关闭 → position 恒 0(未知)
        Map<Long, Integer> posBucket = positionProxy
                ? buildPositionProxy(catMap.keySet(), itemCnt, maxPosition) : Map.of();
        log.info("位置去偏:position-proxy={}(关=position 恒 0,PAL 塔休眠不影响精度),max-position={}",
                positionProxy, maxPosition);

        // 每用户近 seqLen 个 ≥4 物品(point-in-time);最旧在队首、最近在队尾
        Map<Long, Deque<Long>> seqByUser = new HashMap<>();

        Path out = Path.of(OUT);
        Files.createDirectories(out.getParent());
        long posClick = 0, posLike = 0, neg = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            // S2 特征扩充:--extended-features 输出 8 维(供重训扩维模型),默认 5 维(向后兼容)
            List<String> denseOrder = args.containsOption("extended-features")
                    ? FeatureAssembler.EXTENDED_ORDER : FeatureAssembler.FEATURE_ORDER;
            w.write("label_click,label_like,user_id,item_id,category,"
                    + String.join(",", denseOrder)
                    + ",seq_items,seq_cats,seq_len,position,split");
            w.newLine();

            for (Event e : events) {
                // 在 apply 本次事件之前 snapshot:特征与序列都「截至 ts 不含本次」
                Map<String, Double> uf = asOf.snapshotUser(e.userId);
                Deque<Long> dq = seqByUser.get(e.userId);
                String[] seq = encodeSeq(dq, catMap);  // [seq_items, seq_cats, seq_len]

                // 正样本:每条评分事件都是 click 正例;like = (value≥4)
                int like = e.value >= 4.0 ? 1 : 0;
                String split = e.ts <= splitTs ? "train" : "valid";
                int posPosition = posBucket.getOrDefault(e.itemId, 0);
                writeRow(w, 1, like, e.userId, e.itemId, e.category, uf, asOf.snapshotItem(e.itemId),
                        seq, posPosition, split, denseOrder);
                posClick++;
                if (like == 1) {
                    posLike++;
                }

                // 负样本:click=0/like=0,与正样本共享同一用户序列
                Set<Long> rated = ratedByUser.getOrDefault(e.userId, Set.of());
                List<Long> catPool = hardNegRatio > 0 && e.category != null ? itemsByCat.get(e.category) : null;
                int got = 0, tries = 0;
                while (got < negRatio && tries < negRatio * 20) {
                    tries++;
                    long negItem;
                    if (catPool != null && catPool.size() > 1 && rnd.nextDouble() < hardNegRatio) {
                        negItem = catPool.get(rnd.nextInt(catPool.size()));  // hard neg:同类目、更难区分
                    } else {
                        negItem = pool.sample(rnd);                         // 常规:热度加权随机
                    }
                    if (rated.contains(negItem)) {
                        continue;
                    }
                    String negSplit = rnd.nextDouble() < validFrac ? "valid" : "train";
                    writeRow(w, 0, 0, e.userId, negItem, catMap.get(negItem),
                            uf, asOf.snapshotItem(negItem), seq, posBucket.getOrDefault(negItem, 0), negSplit, denseOrder);
                    got++;
                    neg++;
                }

                // apply 本次事件(在 snapshot 之后,保证序列/特征不含本次)
                asOf.apply(e.userId, e.itemId, e.value, e.category);
                if (e.value >= 4.0) {
                    Deque<Long> d = seqByUser.computeIfAbsent(e.userId, k -> new ArrayDeque<>());
                    d.addLast(e.itemId);
                    while (d.size() > seqLen) {
                        d.removeFirst();
                    }
                }
            }
        }
        log.info("gen-samples-mt 完成:click 正例 {}(其中 like {})+ 负 {} = {} 行 → {}",
                posClick, posLike, neg, posClick + neg, out.toAbsolutePath());
    }

    /** 把用户序列(oldest→newest)编码成 [seq_items, seq_cats, seq_len]。空序列 → ["","","0"]。 */
    private String[] encodeSeq(Deque<Long> dq, Map<Long, String> catMap) {
        if (dq == null || dq.isEmpty()) {
            return new String[]{"", "", "0"};
        }
        StringJoiner items = new StringJoiner(String.valueOf(SEQ_DELIM));
        StringJoiner cats = new StringJoiner(String.valueOf(SEQ_DELIM));
        for (Long id : dq) {  // ArrayDeque 迭代 = 队首→队尾 = oldest→newest
            items.add(Long.toString(id));
            String c = catMap.get(id);
            cats.add(c == null ? "" : c);
        }
        return new String[]{items.toString(), cats.toString(), Integer.toString(dq.size())};
    }

    private void writeRow(BufferedWriter w, int click, int like, long userId, long itemId, String category,
                          Map<String, Double> userFeat, Map<String, Double> itemFeat,
                          String[] seq, int position, String split, List<String> denseOrder)
            throws java.io.IOException {
        double[] f = FeatureAssembler.assemble(userFeat, itemFeat, category, denseOrder);
        StringBuilder sb = new StringBuilder();
        sb.append(click).append(',').append(like)
                .append(',').append(userId)
                .append(',').append(itemId)
                .append(',').append(category == null ? "" : category);
        for (double v : f) {
            sb.append(',').append(v);
        }
        sb.append(',').append(seq[0])   // seq_items
                .append(',').append(seq[1])   // seq_cats
                .append(',').append(seq[2])   // seq_len
                .append(',').append(position) // position(PAL;0=未知)
                .append(',').append(split);
        w.write(sb.toString());
        w.newLine();
    }

    /**
     * 热度名次位置代理:按交互次数降序给每个物品分配展示位次桶 [1, maxPosition](热门→1=最靠前)。
     * 仅 --position-proxy 时使用,纯教学(代理与热度特征相关);真实系统应改用曝光日志的真实位次。
     */
    private Map<Long, Integer> buildPositionProxy(Set<Long> items, Map<Long, Long> itemCnt, int maxPosition) {
        List<Long> ranked = new ArrayList<>(items);
        ranked.sort((a, b) -> Long.compare(itemCnt.getOrDefault(b, 0L), itemCnt.getOrDefault(a, 0L)));
        Map<Long, Integer> bucket = new HashMap<>(ranked.size() * 2);
        int n = Math.max(1, ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            int p = 1 + (int) ((long) i * maxPosition / n); // [1, maxPosition]
            bucket.put(ranked.get(i), Math.min(maxPosition, p));
        }
        return bucket;
    }

    /** 全量评分事件(join 类目),供 as-of 流式聚合、序列构造与时间切分。 */
    private List<Event> loadEvents() {
        List<Event> events = new ArrayList<>();
        jdbc.query("SELECT b.user_id, b.item_id, b.value, "
                        + "extract(epoch from b.ts)::bigint AS ts, i.category "
                        + "FROM user_behavior b LEFT JOIN item i ON b.item_id = i.item_id "
                        + "WHERE b.action='RATING'",
                rs -> {
                    events.add(new Event(
                            rs.getLong("user_id"), rs.getLong("item_id"),
                            rs.getDouble("value"), rs.getLong("ts"), rs.getString("category")));
                });
        return events;
    }

    private Map<Long, String> loadCategoryMap() {
        Map<Long, String> map = new HashMap<>();
        jdbc.query("SELECT item_id, category FROM item",
                rs -> {
                    map.put(rs.getLong("item_id"), rs.getString("category"));
                });
        return map;
    }

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

    /** 按权重的前缀和采样池(同 gen-samples)。 */
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
