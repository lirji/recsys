package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.mapper.primary.AdAttributionMapper;
import com.recsys.offline.persistence.model.AdEventSource;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
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
import java.util.Locale;
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
    private static final double SECONDS_PER_DAY = 86_400.0;

    /** 显式数据库事件码，路径内部直接用类型而非数字下标和ordinal。 */
    private enum AttributionKind {
        IMPRESSION("IMPRESSION"), CLICK("CLICK"), CONVERSION("CONVERSION");
        private final String code;
        AttributionKind(String code) { this.code = code; }
        /** SQL限定三类输入，仍保留旧解析对其他值退回曝光的行为。 */
        static AttributionKind fromCode(String code) {
            for (AttributionKind type : values()) if (type.code.equals(code)) return type;
            return IMPRESSION;
        }
    }

    /** 兼容原模型选择与未知值回退，但公开提示不回显任意CLI输入。 */
    private enum AttributionModel {
        LINEAR("linear"), POSITION("position"), TIME_DECAY("time-decay");
        private final String code;
        AttributionModel(String code) { this.code = code; }
        static AttributionModel fromCode(String value) {
            for (AttributionModel model : values()) if (model.code.equals(value.toLowerCase(Locale.ROOT))) return model;
            log.warn("{}", ErrorCode.JOB_ATTRIBUTION_MODEL_FALLBACK.displayMessage());
            return POSITION;
        }
    }

    /** ID必须全程保持long，double只能承载时间和权重，避免相邻大ID被合并。 */
    private record PathEvent(double epoch, long adId, AttributionKind kind) { }
    /** 折叠后的触点保留被选中类型与时间，点击优先规则不变。 */
    private record Touch(double ageDays, AttributionKind kind, double epoch) { }

    private final AdAttributionMapper mapper;

    /** 复用原主源Mapper，报表算法和发布边界保持在作业内。 */
    public AttributionJob(AdAttributionMapper mapper) {
        this.mapper = mapper;
    }

    /** 保留既有离线命令名。 */
    @Override
    public String name() {
        return "ad-attribution";
    }

    /** 身份与输入先校验；报表失败必须传播，不能让Runner记录假完成。 */
    @Override
    public void run(ApplicationArguments args) {
        AdEventSource source = AdEventQuery.source(args);
        int days = intArg(args, "days", 30);
        AttributionModel model = AttributionModel.fromCode(stringArg(args, "model", "position"));
        double halfLife = dblArg(args, "half-life-days", 3.0);
        double clickWindow = dblArg(args, "click-window-days", 7.0);
        double viewWindow = dblArg(args, "view-window-days", 1.0);
        double first = dblArg(args, "first", 0.4);
        double last = dblArg(args, "last", 0.4);
        if (halfLife <= 0 || !Double.isFinite(Math.log(2.0) / halfLife)
                || clickWindow < 0 || viewWindow < 0 || first < 0 || last < 0
                || !Double.isFinite(first + last)) {
            throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        }

        // 按 user_id, ts 升序流式载入窗口内事件(教学规模,内存聚路径)。ep=epoch 秒,便于算 age。
        Map<Long, List<PathEvent>> byUser = new HashMap<>();
        mapper.scan(source, days, context -> {
            var row = context.getResultObject();
            byUser.computeIfAbsent(row.userId(), k -> new ArrayList<>())
                    .add(new PathEvent(row.epoch(), row.adId(), AttributionKind.fromCode(row.eventType())));
        });

        if (byUser.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_NO_ATTRIBUTION_CONVERSIONS);
        }

        Map<Long, Double> lastTouch = new HashMap<>();
        Map<Long, Double> mtaCredit = new HashMap<>();
        Map<Long, Double> ctaCredit = new HashMap<>();
        Map<Long, Double> vtaCredit = new HashMap<>();
        long totalConversions = 0;
        long pathTouchTotal = 0;   // 累计路径触点数(用于报平均路径长度)

        for (List<PathEvent> ev : byUser.values()) {
            for (int k = 0; k < ev.size(); k++) {
                if (ev.get(k).kind() != AttributionKind.CONVERSION) {
                    continue;
                }
                double convEp = ev.get(k).epoch();
                long convAd = ev.get(k).adId();
                lastTouch.merge(convAd, 1.0, Double::sum);
                totalConversions++;

                // 回看窗口内触点,按 adId 折叠(CLICK 优先、同类型取更近);best按完整long广告ID归并
                Map<Long, Touch> best = new LinkedHashMap<>();
                for (int j = 0; j < k; j++) {
                    long adId = ev.get(j).adId();
                    AttributionKind kind = ev.get(j).kind();
                    if (kind == AttributionKind.CONVERSION) {
                        continue;
                    }
                    double ep = ev.get(j).epoch();
                    double ageDays = (convEp - ep) / SECONDS_PER_DAY;
                    boolean qualifies = (kind == AttributionKind.CLICK && ageDays <= clickWindow)
                            || (kind == AttributionKind.IMPRESSION && ageDays <= viewWindow);
                    if (!qualifies) {
                        continue;
                    }
                    Touch cur = best.get(adId);
                    if (cur == null) {
                        best.put(adId, new Touch(ageDays, kind, ep));
                    } else {
                        AttributionKind curType = cur.kind();
                        boolean better = (kind == AttributionKind.CLICK && curType == AttributionKind.IMPRESSION)   // 点击胜过曝光
                                || (kind == curType && ageDays < cur.ageDays());           // 同类型取更近
                        if (better) {
                            best.put(adId, new Touch(ageDays, kind, ep));
                        }
                    }
                }
                // 窗口内无触点 → 回退把信用给转化广告本身(等价 last-touch)
                if (best.isEmpty()) {
                    best.put(convAd, new Touch(0.0, AttributionKind.CLICK, convEp));
                }

                // 按 ep 升序成路径(首触点=最早)
                List<Map.Entry<Long, Touch>> path = new ArrayList<>(best.entrySet());
                path.sort(Comparator.comparingDouble(e -> e.getValue().epoch()));
                int n = path.size();
                pathTouchTotal += n;

                double[] weights;
                switch (model) {
                    case LINEAR -> weights = MultiTouchAttribution.linearWeights(n);
                    case TIME_DECAY -> {
                        double[] ages = new double[n];
                        for (int i = 0; i < n; i++) {
                            ages[i] = path.get(i).getValue().ageDays();
                        }
                        weights = MultiTouchAttribution.timeDecayWeights(ages, halfLife);
                    }
                    default -> weights = MultiTouchAttribution.positionBasedWeights(n, first, last);
                }

                for (int i = 0; i < n; i++) {
                    long adId = path.get(i).getKey();
                    AttributionKind type = path.get(i).getValue().kind();
                    double w = weights[i];
                    mtaCredit.merge(adId, w, Double::sum);
                    if (type == AttributionKind.CLICK) {
                        ctaCredit.merge(adId, w, Double::sum);
                    } else {
                        vtaCredit.merge(adId, w, Double::sum);
                    }
                }
            }
        }

        if (totalConversions == 0) {
            throw new PublicErrorException(ErrorCode.JOB_NO_ATTRIBUTION_CONVERSIONS);
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
        } catch (IOException failure) {
            throw new PublicErrorException(ErrorCode.JOB_REPORT_OUTPUT_FAILED, failure);
        }

        log.info("ad-attribution 完成:model={},转化 {},MTA 信用和 {}(应≈转化数,守恒自检),"
                        + "平均路径 {} 触点,窗口 CTA={}d/VTA={}d。明细 CSV: {}",
                model.code, totalConversions, round4(mtaSum),
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
        return JobArguments.integer(a, k, def);
    }

    private static double dblArg(ApplicationArguments a, String k, double def) {
        return JobArguments.decimal(a, k, def);
    }

    private static String stringArg(ApplicationArguments a, String k, String def) {
        return JobArguments.text(a, k, def);
    }
}
