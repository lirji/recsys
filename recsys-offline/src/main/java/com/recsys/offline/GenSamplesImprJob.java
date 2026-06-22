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
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 作业 gen-samples-impr:<b>曝光日志闭环</b> —— 从真实 {@code IMPRESSION} 日志构造多目标 + 序列样本,
 * 写 {@code train/samples_mt.csv}(与 {@link GenSamplesMtJob} 同表头/同契约,trainers 零改动直接消费)。
 *
 * <p>这是 {@code gen-samples-mt}(由 MovieLens 评分派生)的<b>闭环替代</b>。区别与价值:
 * <ul>
 *   <li><b>真实负样本</b>:曝光未点击(展示了但没正反馈)= 天然负样本,不再靠热度采样近似;</li>
 *   <li><b>真实位次</b>:每条曝光带 {@code position}(展示排名)→ 直接喂 PAL 位置去偏(不再休眠);</li>
 *   <li><b>标签来自归因</b>:曝光后 {@code window} 内该 (user,item) 有 CLICK/PLAY/RATING → {@code label_click=1};
 *       有 LIKE 或 RATING≥4 → {@code label_like=1}(ESMM 的 click/like 双标签)。</li>
 * </ul>
 *
 * <p><b>as-of 无穿越</b>:把 RATING 事件按 ts 升序流式并入 {@link AsOfFeatureBuilder} 与行为序列;
 * 每条曝光在其 ts <em>之前</em>的状态上 snapshot 特征/序列(同 {@code gen-samples-mt})。
 *
 * <p><b>引导依赖</b>:需要已积累的曝光日志(线上 {@code /api/recommend} 服务流量,或教学用
 * {@code --job=sim-rec-events} 造带位置偏置的曝光+点击)。无 IMPRESSION → 直接提示并退出。
 *
 * <p>参数:--valid-frac(默认 0.2)、--seed(默认 42)、--seq-len(默认 20)、
 * --window-hours(归因窗口,默认 24;<=0 表示曝光后任意时间)、--max-position(默认 10)。
 */
@Component
public class GenSamplesImprJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(GenSamplesImprJob.class);
    private static final String OUT = "train/samples_mt.csv";
    private static final int DEFAULT_SEQ_LEN = 20;
    private static final char SEQ_DELIM = '|';

    private final JdbcTemplate jdbc;

    public GenSamplesImprJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "gen-samples-impr";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        double validFrac = doubleArg(args, "valid-frac", 0.2);
        int seqLen = intArg(args, "seq-len", DEFAULT_SEQ_LEN);
        int windowHours = intArg(args, "window-hours", 24);
        int maxPosition = intArg(args, "max-position", 10);
        long windowSec = windowHours > 0 ? (long) windowHours * 3600 : Long.MAX_VALUE;

        List<Impr> imprs = loadImpressions();
        if (imprs.isEmpty()) {
            log.warn("无 IMPRESSION 日志;先让 /api/recommend 产生曝光,或跑 --job=sim-rec-events(教学)");
            return;
        }
        List<Event> ratings = loadRatings();      // 仅用于 as-of 特征/序列累加
        Positives pos = loadPositives();          // (user,item) → 正反馈 ts 列表(归因标签)

        // as-of 归一分母(口径同 gen-samples-mt)
        Map<Long, Long> userCnt = new HashMap<>();
        Map<Long, Long> itemCnt = new HashMap<>();
        for (Event e : ratings) {
            userCnt.merge(e.userId, 1L, Long::sum);
            itemCnt.merge(e.itemId, 1L, Long::sum);
        }
        long maxU = userCnt.values().stream().mapToLong(Long::longValue).max().orElse(1);
        long maxI = itemCnt.values().stream().mapToLong(Long::longValue).max().orElse(1);
        AsOfFeatureBuilder asOf = new AsOfFeatureBuilder(Math.log1p(maxU), Math.log1p(maxI));
        Map<Long, String> catMap = loadCategoryMap();
        Map<Long, Deque<Long>> seqByUser = new HashMap<>();

        // 合并 RATING(apply)与 IMPRESSION(emit)为按 ts 升序的事件流;同 ts 时 IMPRESSION 先(避免穿越)
        List<long[]> stream = new ArrayList<>(); // [ts, type(0=impr,1=rating), idx]
        for (int i = 0; i < imprs.size(); i++) {
            stream.add(new long[]{imprs.get(i).ts, 0, i});
        }
        for (int i = 0; i < ratings.size(); i++) {
            stream.add(new long[]{ratings.get(i).ts, 1, i});
        }
        stream.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));

        java.util.Random rnd = new java.util.Random((long) doubleArg(args, "seed", 42));
        Path out = Path.of(OUT);
        Files.createDirectories(out.getParent());
        long rows = 0, posClick = 0, posLike = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("label_click,label_like,user_id,item_id,category,"
                    + String.join(",", FeatureAssembler.FEATURE_ORDER)
                    + ",seq_items,seq_cats,seq_len,position,split");
            w.newLine();

            for (long[] ev : stream) {
                if (ev[1] == 1) {                       // RATING:并入 as-of 状态 + 序列
                    Event e = ratings.get((int) ev[2]);
                    asOf.apply(e.userId, e.itemId, e.value, e.category);
                    if (e.value >= 4.0) {
                        Deque<Long> d = seqByUser.computeIfAbsent(e.userId, k -> new ArrayDeque<>());
                        d.addLast(e.itemId);
                        while (d.size() > seqLen) {
                            d.removeFirst();
                        }
                    }
                    continue;
                }
                // IMPRESSION:在此刻(不含本次及之后事件)snapshot,构造一条样本
                Impr im = imprs.get((int) ev[2]);
                int[] label = pos.label(im.userId, im.itemId, im.ts, windowSec);
                Map<String, Double> uf = asOf.snapshotUser(im.userId);
                Map<String, Double> itf = asOf.snapshotItem(im.itemId);
                String[] seq = encodeSeq(seqByUser.get(im.userId), catMap);
                String split = rnd.nextDouble() < validFrac ? "valid" : "train";
                int position = Math.max(0, Math.min(maxPosition, im.position));
                writeRow(w, label[0], label[1], im.userId, im.itemId, catMap.get(im.itemId),
                        uf, itf, seq, position, split);
                rows++;
                posClick += label[0];
                posLike += label[1];
            }
        }
        log.info("gen-samples-impr 完成:曝光样本 {} 行(click 正例 {} / like 正例 {},负 {})→ {}",
                rows, posClick, posLike, rows - posClick, out.toAbsolutePath());
        if (posClick == 0) {
            log.warn("⚠️ 全是负样本:曝光后窗口内无正反馈归因(MovieLens 评分多早于曝光)。"
                    + "教学演示请先跑 --job=sim-rec-events 造带点击的曝光日志。");
        }
    }

    private String[] encodeSeq(Deque<Long> dq, Map<Long, String> catMap) {
        if (dq == null || dq.isEmpty()) {
            return new String[]{"", "", "0"};
        }
        StringJoiner items = new StringJoiner(String.valueOf(SEQ_DELIM));
        StringJoiner cats = new StringJoiner(String.valueOf(SEQ_DELIM));
        for (Long id : dq) {
            items.add(Long.toString(id));
            String c = catMap.get(id);
            cats.add(c == null ? "" : c);
        }
        return new String[]{items.toString(), cats.toString(), Integer.toString(dq.size())};
    }

    private void writeRow(BufferedWriter w, int click, int like, long userId, long itemId, String category,
                          Map<String, Double> userFeat, Map<String, Double> itemFeat,
                          String[] seq, int position, String split) throws java.io.IOException {
        double[] f = FeatureAssembler.assemble(userFeat, itemFeat, category);
        StringBuilder sb = new StringBuilder();
        sb.append(click).append(',').append(like)
                .append(',').append(userId)
                .append(',').append(itemId)
                .append(',').append(category == null ? "" : category);
        for (double v : f) {
            sb.append(',').append(v);
        }
        sb.append(',').append(seq[0]).append(',').append(seq[1]).append(',').append(seq[2])
                .append(',').append(position).append(',').append(split);
        w.write(sb.toString());
        w.newLine();
    }

    private List<Impr> loadImpressions() {
        List<Impr> out = new ArrayList<>();
        jdbc.query("SELECT user_id, item_id, COALESCE(position, value::int, 0) AS position, "
                        + "extract(epoch from ts)::bigint AS ts "
                        + "FROM user_behavior WHERE action='IMPRESSION'",
                rs -> {
                    out.add(new Impr(rs.getLong("user_id"), rs.getLong("item_id"),
                            rs.getInt("position"), rs.getLong("ts")));
                });
        return out;
    }

    private List<Event> loadRatings() {
        List<Event> out = new ArrayList<>();
        jdbc.query("SELECT b.user_id, b.item_id, b.value, extract(epoch from b.ts)::bigint AS ts, i.category "
                        + "FROM user_behavior b LEFT JOIN item i ON b.item_id = i.item_id "
                        + "WHERE b.action='RATING'",
                rs -> {
                    out.add(new Event(rs.getLong("user_id"), rs.getLong("item_id"),
                            rs.getDouble("value"), rs.getLong("ts"), rs.getString("category")));
                });
        return out;
    }

    /** 加载正反馈事件(归因标签源):click 类 = CLICK/PLAY/RATING;like 类 = LIKE/RATING≥4。 */
    private Positives loadPositives() {
        Positives p = new Positives();
        jdbc.query("SELECT user_id, item_id, action, COALESCE(value,0) AS value, "
                        + "extract(epoch from ts)::bigint AS ts "
                        + "FROM user_behavior WHERE action IN ('CLICK','LIKE','PLAY','RATING')",
                rs -> {
                    String action = rs.getString("action");
                    long ts = rs.getLong("ts");
                    boolean like = "LIKE".equals(action)
                            || ("RATING".equals(action) && rs.getDouble("value") >= 4.0);
                    p.add(rs.getLong("user_id"), rs.getLong("item_id"), ts, like);
                });
        return p;
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

    private record Impr(long userId, long itemId, int position, long ts) {
    }

    /** (user,item) → 正反馈 ts 列表(click 类 / like 类),按曝光后窗口归因出 click/like 标签。 */
    private static final class Positives {
        // userId -> itemId -> [ts...](click 类);like 类单独存
        private final Map<Long, Map<Long, List<long[]>>> map = new HashMap<>();

        void add(long userId, long itemId, long ts, boolean like) {
            map.computeIfAbsent(userId, k -> new HashMap<>())
                    .computeIfAbsent(itemId, k -> new ArrayList<>())
                    .add(new long[]{ts, like ? 1 : 0});
        }

        /** 曝光 (user,item,imprTs) 在窗口 (imprTs, imprTs+windowSec] 内是否有 click / like。 */
        int[] label(long userId, long itemId, long imprTs, long windowSec) {
            Map<Long, List<long[]>> byItem = map.get(userId);
            if (byItem == null) {
                return new int[]{0, 0};
            }
            List<long[]> acts = byItem.get(itemId);
            if (acts == null) {
                return new int[]{0, 0};
            }
            int click = 0, like = 0;
            long hi = windowSec == Long.MAX_VALUE ? Long.MAX_VALUE : imprTs + windowSec;
            for (long[] a : acts) {
                if (a[0] > imprTs && a[0] <= hi) {
                    click = 1;
                    if (a[1] == 1) {
                        like = 1;
                    }
                }
            }
            return new int[]{click, like};
        }
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static double doubleArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }
}
