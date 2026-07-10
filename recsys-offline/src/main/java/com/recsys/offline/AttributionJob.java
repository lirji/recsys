package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作业 ad-attribution:**多触点归因(MTA,A5)**。把每次转化的 1.0 信用按选定模型
 * (linear / position / time-decay)分配到<b>转化路径</b>上的各触点(广告曝光/点击),
 * 与现有 last-touch(只记末触点)对账,给广告主更公平的渠道价值度量。docs/05 §7。
 *
 * <p><b>路径重建</b>:仅用 {@code ad_event}(无 session/advertiser 列,故按 {@code user_id} 聚路径)。
 * 对每个用户按 {@code ts} 排序,对每次 CONVERSION 回看其之前、窗口内的触点:
 * <ul>
 *   <li><b>点击后归因 CTA</b>:CLICK 触点,{@code age ≤ click-window-days};</li>
 *   <li><b>浏览后归因 VTA</b>:IMPRESSION 触点(未点击),{@code age ≤ view-window-days}(通常更短)。</li>
 * </ul>
 * 同一广告在路径里多次出现则折叠成一个触点(CLICK 优先于 IMPRESSION、同类型取更近的一次),
 * 按时间升序成路径(首触点=最早、末触点=转化前最后);若窗口内无触点则回退把信用给转化广告本身
 * (等价 last-touch)。每次转化的权重和恒为 1 → {@code Σ mta_credit ≈ 总转化数}(与 last-touch 同量,可对账)。
 *
 * <p><b>输出</b>:{@code eval/ad-attribution-<ts>.csv},每广告一行:last-touch 转化数、MTA 信用、
 * CTA/VTA 信用拆分、以及 {@code credit_delta = mta − last_touch}(正=该广告作为"前期种草"被 last-touch 低估、
 * MTA 补回;负=该广告靠"临门一脚"被 last-touch 高估)。纯统计报表,不写在线存储、不参与计费。
 *
 * <p>参数:--days(窗口,默认 30;&le;0 全量)、--model=linear|position|time-decay(默认 position)、
 * --half-life-days(time-decay 半衰期,默认 3)、--click-window-days(CTA 回看,默认 7)、
 * --view-window-days(VTA 回看,默认 1)、--first/--last(position 首末权重,默认 0.4/0.4)。
 */
@Component
public class AttributionJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(AttributionJob.class);
    private static final String OUT_DIR = "eval";
    private static final int IMPRESSION = 0, CLICK = 1, CONVERSION = 2;

    private final JdbcTemplate jdbc;   // 主库:ad_event 单表(ds_0),无需分片库

    public AttributionJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "ad-attribution";
    }

    @Override
    public void run(ApplicationArguments args) {
        int days = intArg(args, "days", 30);
        String model = stringArg(args, "model", "position").toLowerCase();
        double halfLife = dblArg(args, "half-life-days", 3.0);
        double clickWindow = dblArg(args, "click-window-days", 7.0);
        double viewWindow = dblArg(args, "view-window-days", 1.0);
        double first = dblArg(args, "first", 0.4);
        double last = dblArg(args, "last", 0.4);
        if (!model.equals("linear") && !model.equals("position") && !model.equals("time-decay")) {
            log.warn("未知 model={},支持 linear|position|time-decay;退回 position", model);
            model = "position";
        }
        String since = days > 0 ? "ts >= now() - interval '" + days + " days'" : "TRUE";

        // 按 user_id, ts 升序流式载入窗口内事件(教学规模,内存聚路径)。ep=epoch 秒,便于算 age。
        Map<Long, List<double[]>> byUser = new HashMap<>();  // user → [ep, adId, typeCode] 列表(已按 ts 升序)
        jdbc.query("SELECT user_id, ad_id, event_type, EXTRACT(EPOCH FROM ts) AS ep FROM ad_event " +
                        "WHERE event_type IN ('IMPRESSION','CLICK','CONVERSION') AND " + since +
                        " ORDER BY user_id, ep",
                (RowCallbackHandler) rs -> {
                    long u = rs.getLong("user_id");
                    long adId = rs.getLong("ad_id");
                    double ep = rs.getDouble("ep");
                    String t = rs.getString("event_type");
                    int code = "CONVERSION".equals(t) ? CONVERSION : "CLICK".equals(t) ? CLICK : IMPRESSION;
                    byUser.computeIfAbsent(u, k -> new ArrayList<>()).add(new double[]{ep, adId, code});
                });

        if (byUser.isEmpty()) {
            log.warn("无广告事件,先跑 --job=sim-ad-events(或灌真实曝光/点击/转化)");
            return;
        }

        Map<Long, Double> lastTouch = new HashMap<>();
        Map<Long, Double> mtaCredit = new HashMap<>();
        Map<Long, Double> ctaCredit = new HashMap<>();
        Map<Long, Double> vtaCredit = new HashMap<>();
        long totalConversions = 0;
        long pathTouchTotal = 0;   // 累计路径触点数(用于报平均路径长度)

        for (List<double[]> ev : byUser.values()) {
            for (int k = 0; k < ev.size(); k++) {
                if ((int) ev.get(k)[2] != CONVERSION) {
                    continue;
                }
                double convEp = ev.get(k)[0];
                long convAd = (long) ev.get(k)[1];
                lastTouch.merge(convAd, 1.0, Double::sum);
                totalConversions++;

                // 回看窗口内触点,按 adId 折叠(CLICK 优先、同类型取更近);best: adId → [ageDays, typeCode, ep]
                Map<Long, double[]> best = new LinkedHashMap<>();
                for (int j = 0; j < k; j++) {
                    long adId = (long) ev.get(j)[1];
                    int code = (int) ev.get(j)[2];
                    if (code == CONVERSION) {
                        continue;
                    }
                    double ep = ev.get(j)[0];
                    double ageDays = (convEp - ep) / 86400.0;
                    boolean qualifies = (code == CLICK && ageDays <= clickWindow)
                            || (code == IMPRESSION && ageDays <= viewWindow);
                    if (!qualifies) {
                        continue;
                    }
                    double[] cur = best.get(adId);
                    if (cur == null) {
                        best.put(adId, new double[]{ageDays, code, ep});
                    } else {
                        int curType = (int) cur[1];
                        boolean better = (code == CLICK && curType == IMPRESSION)   // 点击胜过曝光
                                || (code == curType && ageDays < cur[0]);           // 同类型取更近
                        if (better) {
                            best.put(adId, new double[]{ageDays, code, ep});
                        }
                    }
                }
                // 窗口内无触点 → 回退把信用给转化广告本身(等价 last-touch)
                if (best.isEmpty()) {
                    best.put(convAd, new double[]{0.0, CLICK, convEp});
                }

                // 按 ep 升序成路径(首触点=最早)
                List<Map.Entry<Long, double[]>> path = new ArrayList<>(best.entrySet());
                path.sort(Comparator.comparingDouble(e -> e.getValue()[2]));
                int n = path.size();
                pathTouchTotal += n;

                double[] weights;
                switch (model) {
                    case "linear" -> weights = MultiTouchAttribution.linearWeights(n);
                    case "time-decay" -> {
                        double[] ages = new double[n];
                        for (int i = 0; i < n; i++) {
                            ages[i] = path.get(i).getValue()[0];
                        }
                        weights = MultiTouchAttribution.timeDecayWeights(ages, halfLife);
                    }
                    default -> weights = MultiTouchAttribution.positionBasedWeights(n, first, last);
                }

                for (int i = 0; i < n; i++) {
                    long adId = path.get(i).getKey();
                    int type = (int) path.get(i).getValue()[1];
                    double w = weights[i];
                    mtaCredit.merge(adId, w, Double::sum);
                    if (type == CLICK) {
                        ctaCredit.merge(adId, w, Double::sum);
                    } else {
                        vtaCredit.merge(adId, w, Double::sum);
                    }
                }
            }
        }

        if (totalConversions == 0) {
            log.warn("窗口内无转化事件(CONVERSION),无从归因;先跑 --job=sim-ad-events");
            return;
        }

        Path outFile = Path.of(OUT_DIR,
                "ad-attribution-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");
        double mtaSum = mtaCredit.values().stream().mapToDouble(Double::doubleValue).sum();
        try {
            Files.createDirectories(outFile.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
                w.write("ad_id,conversions_last_touch,mta_credit,cta_credit,vta_credit,credit_delta");
                w.newLine();
                // 覆盖两侧出现过的广告(有的广告只在路径里当种草触点、last-touch 为 0)
                List<Long> ads = new ArrayList<>(mtaCredit.keySet());
                for (Long a : lastTouch.keySet()) {
                    if (!mtaCredit.containsKey(a)) {
                        ads.add(a);
                    }
                }
                ads.sort(Comparator.comparingDouble((Long a) -> mtaCredit.getOrDefault(a, 0.0)).reversed());
                for (Long a : ads) {
                    double lt = lastTouch.getOrDefault(a, 0.0);
                    double mta = mtaCredit.getOrDefault(a, 0.0);
                    double cta = ctaCredit.getOrDefault(a, 0.0);
                    double vta = vtaCredit.getOrDefault(a, 0.0);
                    w.write(a + "," + (long) lt + "," + round4(mta) + "," + round4(cta) + ","
                            + round4(vta) + "," + round4(mta - lt));
                    w.newLine();
                }
            }
        } catch (Exception ex) {
            log.warn("ad-attribution 写 CSV 失败: {}", ex.getMessage());
        }

        log.info("ad-attribution 完成:model={},转化 {},MTA 信用和 {}(应≈转化数,守恒自检),"
                        + "平均路径 {} 触点,窗口 CTA={}d/VTA={}d。明细 CSV: {}",
                model, totalConversions, round4(mtaSum),
                round2((double) pathTouchTotal / totalConversions), clickWindow, viewWindow,
                outFile.toAbsolutePath());
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

    private static double dblArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }

    private static String stringArg(ApplicationArguments a, String k, String def) {
        return a.containsOption(k) && !a.getOptionValues(k).isEmpty()
                ? a.getOptionValues(k).get(0).trim() : def;
    }
}
