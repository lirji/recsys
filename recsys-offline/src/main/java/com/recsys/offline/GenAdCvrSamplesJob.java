package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import com.recsys.common.feature.FeatureService;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import com.recsys.rank.FeatureAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.recsys.offline.persistence.mapper.primary.AdCvrInputMapper;
import com.recsys.offline.persistence.mapper.primary.AdCvrInputMapper.Click;
import com.recsys.offline.persistence.mapper.sharded.AdItemMapper;
import com.recsys.offline.persistence.model.AdEventSource;
import org.springframework.boot.ApplicationArguments;
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

    private final AdCvrInputMapper events; // 主库事件与读仓共享原资源身份
    private final AdItemMapper ads;        // 逻辑分片源覆盖ds_0/ds_1
    private final FeatureService featureService;
    private final ContentService contentService;

    /** 两路Mapper分别沿原主库与逻辑分片源读取，特征服务边界不变。 */
    public GenAdCvrSamplesJob(AdCvrInputMapper events, AdItemMapper ads,
                              FeatureService featureService, ContentService contentService) {
        this.events = events;
        this.ads = ads;
        this.featureService = featureService;
        this.contentService = contentService;
    }

    /** 保留既有离线命令名。 */
    @Override
    public String name() {
        return "gen-ad-cvr-samples";
    }

    /** 先读取原删失输入，再按既有特征和随机切分口径写出样本。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        AdEventSource source = AdEventQuery.source(args);
        int days = JobArguments.integer(args, "days", 30);
        long seed = JobArguments.integer(args, "seed", 42);

        // 1. ad_id → item_id(分片 ad 表全量)
        Map<Long, Long> adToItem = new HashMap<>();
        ads.scanLinks(context -> {
            var row = context.getResultObject();
            adToItem.put(row.adId(), row.itemId());
        });

        // 2. 原规则只限制转化已到达，不要求转化晚于点击；同请求/广告取最早值。
        Map<String, Double> convEp = new HashMap<>();
        events.scanConversions(source, context -> {
            var row = context.getResultObject();
            convEp.merge(row.requestId() + ":" + row.adId(), row.epoch(), Math::min);
        });

        // 3. 仍在打开CSV前读齐点击，保持查询失败时旧文件不变。
        List<Click> clicks = events.loadClicks(source, days);
        if (clicks.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_NO_AD_CLICKS);
        }

        // 4. 批量预取候选 item 特征 + 类目(经 ad.item_id)
        Set<Long> itemSet = new HashSet<>();
        for (Click c : clicks) {
            Long itemId = adToItem.get(c.adId());
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
        Files.createDirectories(out.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("converted,delay_days,elapsed_days,user_id,item_id,category," + String.join(",", order) + ",split");
            w.newLine();
            for (Click c : clicks) {
                String reqId = c.requestId();
                long userId = c.userId();
                long adId = c.adId();
                double clickEp = c.epoch();
                double elapsed = c.elapsedDays();
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

        if (written == 0) throw new PublicErrorException(ErrorCode.JOB_NO_SAMPLE_OUTPUT);
        if (noItem > 0) log.warn("{}", ErrorCode.JOB_SAMPLES_MISSING_ITEM.displayMessage());
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

}
