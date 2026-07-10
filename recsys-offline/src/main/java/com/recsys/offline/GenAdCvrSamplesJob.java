package com.recsys.offline;

import com.recsys.common.feature.FeatureService;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import com.recsys.rank.FeatureAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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
 * 作业 gen-ad-cvr-samples:为 A6 延迟反馈 DFM({@code train_dfm.py})造**带删失标签**的广告点击 CVR 样本。
 *
 * <p>每条 {@code ad_event} 的 CLICK 是一个样本:上下文 = 该点击的 (user, ad.item_id) 特征;标签 = 是否
 * **已到达**转化 + 延迟。DFM 需要的删失信号:
 * <ul>
 *   <li>{@code converted}:该点击是否有匹配 CONVERSION(join {@code request_id+ad_id},且 {@code ts<=now()} 右删失);</li>
 *   <li>{@code delay_days}:已转化则 {@code (conv.ts−click.ts)},否则 -1;</li>
 *   <li>{@code elapsed_days}:{@code now()−click.ts}(观测时长,删失样本据此判断"还判断不了")。</li>
 * </ul>
 *
 * <p><b>在线/离线一致性</b>:特征 x 用与在线 {@link com.recsys.ad.DfmCvrService} <b>同一</b>
 * {@link FeatureAssembler}(默认 {@code FEATURE_ORDER} 5 维)从 {@link FeatureService}(feat:*)+ item 类目装配。
 * 广告经 {@code ad.item_id} 借用真实 item 特征。{@code ad_event}(主库单表)无 item_id → 从**分片** {@code ad} 表
 * 解析 {@code ad_id→item_id}(同 {@code SimAdEventsJob}/{@code OcpcCalibrateJob} 的双数据源)。
 *
 * <p>输出 {@code train/ad_cvr_samples.csv}(表头 {@code converted,delay_days,elapsed_days,user_id,item_id,
 * category,<FEATURE_ORDER 5>,split})。无 CLICK → 提示先 {@code --job=sim-ad-events}(它造点击 + 指数延迟 +
 * 右删失转化,正是 DFM 训练信号)。参数:--days(点击窗口,默认 30;&le;0 全量)、--seed(默认 42)。
 */
@Component
public class GenAdCvrSamplesJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(GenAdCvrSamplesJob.class);
    private static final String OUT = "train/ad_cvr_samples.csv";

    private final JdbcTemplate jdbc;       // 主库:ad_event(单表 ds_0)
    private final JdbcTemplate sharded;    // 分片库:ad(ds_0/ds_1)→ item_id
    private final FeatureService featureService;
    private final ContentService contentService;

    public GenAdCvrSamplesJob(JdbcTemplate jdbc,
                              @Qualifier("adShardingJdbc") JdbcTemplate sharded,
                              FeatureService featureService, ContentService contentService) {
        this.jdbc = jdbc;
        this.sharded = sharded;
        this.featureService = featureService;
        this.contentService = contentService;
    }

    @Override
    public String name() {
        return "gen-ad-cvr-samples";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int days = intArg(args, "days", 30);
        long seed = intArg(args, "seed", 42);
        String sinceClick = days > 0 ? "ts >= now() - interval '" + days + " days'" : "TRUE";

        // 1. ad_id → item_id(分片 ad 表全量)
        Map<Long, Long> adToItem = new HashMap<>();
        sharded.query("SELECT ad_id, item_id FROM ad WHERE item_id IS NOT NULL",
                (RowCallbackHandler) rs -> adToItem.put(rs.getLong("ad_id"), rs.getLong("item_id")));

        // 2. 已到达转化(ts<=now 右删失;不下界——转化晚于点击,匹配任一在窗点击):key=request_id:ad_id → 最早 conv epoch
        Map<String, Double> convEp = new HashMap<>();
        jdbc.query("SELECT request_id, ad_id, EXTRACT(EPOCH FROM ts) AS conv_ep FROM ad_event " +
                        "WHERE event_type='CONVERSION' AND ts <= now()",
                (RowCallbackHandler) rs -> convEp.merge(
                        rs.getString("request_id") + ":" + rs.getLong("ad_id"), rs.getDouble("conv_ep"), Math::min));

        // 3. 点击(窗口内)+ click epoch + elapsed
        List<Object[]> clicks = new ArrayList<>();   // [request_id, user_id, ad_id, click_ep, elapsed_days]
        jdbc.query("SELECT request_id, user_id, ad_id, EXTRACT(EPOCH FROM ts) AS click_ep, " +
                        "EXTRACT(EPOCH FROM (now()-ts))/86400.0 AS elapsed_days FROM ad_event " +
                        "WHERE event_type='CLICK' AND " + sinceClick,
                (RowCallbackHandler) rs -> clicks.add(new Object[]{
                        rs.getString("request_id"), rs.getLong("user_id"), rs.getLong("ad_id"),
                        rs.getDouble("click_ep"), rs.getDouble("elapsed_days")}));
        if (clicks.isEmpty()) {
            log.warn("无 CLICK 事件;先跑 --job=sim-ad-events(造点击+指数延迟+右删失转化),或灌真实广告点击/转化");
            return;
        }

        // 4. 批量预取候选 item 特征 + 类目(经 ad.item_id)
        Set<Long> itemSet = new HashSet<>();
        for (Object[] c : clicks) {
            Long itemId = adToItem.get((Long) c[2]);
            if (itemId != null) {
                itemSet.add(itemId);
            }
        }
        Map<Long, Map<String, Double>> itemFeats = featureService.itemFeatures(itemSet);
        Map<Long, Item> items = contentService.findByIds(new ArrayList<>(itemSet));
        Map<Long, Map<String, Double>> userCache = new HashMap<>();

        List<String> order = FeatureAssembler.FEATURE_ORDER;
        Random rng = new Random(seed);
        Path out = Path.of(OUT);
        int written = 0, converted = 0, noItem = 0;
        try {
            Files.createDirectories(out.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                w.write("converted,delay_days,elapsed_days,user_id,item_id,category," + String.join(",", order) + ",split");
                w.newLine();
                for (Object[] c : clicks) {
                    String reqId = (String) c[0];
                    long userId = (Long) c[1];
                    long adId = (Long) c[2];
                    double clickEp = (Double) c[3];
                    double elapsed = (Double) c[4];
                    Long itemId = adToItem.get(adId);
                    if (itemId == null) {
                        noItem++;
                        continue;
                    }
                    Double convE = convEp.get(reqId + ":" + adId);
                    int conv = convE != null ? 1 : 0;
                    double delay = convE != null ? (convE - clickEp) / 86400.0 : -1.0;
                    if (conv == 1) {
                        converted++;
                    }
                    Item it = items.get(itemId);
                    String cat = it == null ? null : it.category();
                    Map<String, Double> uf = userCache.computeIfAbsent(userId, featureService::userFeatures);
                    double[] f = FeatureAssembler.assemble(uf, itemFeats.getOrDefault(itemId, Map.of()), cat, order);

                    StringBuilder sb = new StringBuilder();
                    sb.append(conv).append(',').append(round5(delay)).append(',').append(round5(elapsed))
                            .append(',').append(userId).append(',').append(itemId).append(',')
                            .append(cat == null ? "" : cat);
                    for (double v : f) {
                        sb.append(',').append(round5(v));
                    }
                    sb.append(',').append(rng.nextDouble() < 0.85 ? "train" : "valid");
                    w.write(sb.toString());
                    w.newLine();
                    written++;
                }
            }
        } catch (Exception ex) {
            log.warn("gen-ad-cvr-samples 写 CSV 失败: {}", ex.getMessage());
            throw ex;
        }

        log.info("gen-ad-cvr-samples 完成:点击 {},写样本 {}(已到达转化 {},观测转化率 {} —— 被延迟删失低估,"
                        + "DFM 训练后 pCVR 会回升),无关联 item 跳过 {}。→ {}",
                clicks.size(), written, converted,
                written > 0 ? round4((double) converted / written) : 0.0, noItem, out.toAbsolutePath());
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static double round5(double v) {
        return Math.round(v * 100000) / 100000.0;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }
}
