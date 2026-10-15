package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.AdSimulationWriter;
import com.recsys.offline.persistence.mapper.addb.AdSimulationMapper;
import com.recsys.offline.persistence.mapper.sharded.AdSimulationCatalogMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 作业 sim-ad-events:**教学用**广告曝光/点击模拟,给 {@link AdCalibrateJob} 喂校准所需的数据。
 *
 * <p><b>非真实流量</b>——本地无真实广告点击日志,这里人造一个"已知真值 + 系统性高估"的场景来演示校准:
 * <ul>
 *   <li>真值点击率 {@code trueCtr ~ U(0.02,0.4)};</li>
 *   <li>模型预估 {@code pctr = trueCtr^0.7}(刻意做成系统性高估,制造校准缺口);</li>
 *   <li>点击 ~ Bernoulli(trueCtr)。</li>
 * </ul>
 * 于是 ad_event 里 pctr 普遍高于经验 CTR,保序回归应学到把 pctr 往下校。生产中应换成真实
 * IMPRESSION/CLICK 日志(本作业仅用于打通校准链路 demo)。
 *
 * <p><b>延迟转化(docs/05 §6,M6)</b>:曝光/点击在过去 {@code --days} 天内均匀铺开,
 * 每次点击的转化延迟 ~ 指数分布(均值 {@code --conv-delay-days} 天),转化时刻 = 点击时刻 + 延迟。
 * 转化按其<b>真实(可能未来)时刻</b>落库——晚于 now() 的即"尚未到达"(右删失),下游查询用
 * {@code ts <= now()} 只看已观测部分,于是 ad-ocpc 直接数转化会系统性偏低(这正是延迟反馈偏差)。
 * {@code ad-delay} 拟合延迟分布、{@code ad-ocpc --delay-correct} 用完成曲线把它纠偏回来。
 *
 * <p>参数:--requests(默认 20000 次曝光)、--seed(默认 7)、--days(事件铺开窗口,默认 14)、
 * --conv-delay-days(平均转化延迟天数,默认 3)。
 */
@Component
public class SimAdEventsJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(SimAdEventsJob.class);

    private final AdSimulationWriter writer;
    private final AdSimulationCatalogMapper catalog;

    /** 广告事件批写与分片目录查询分开，保留原广告自有源/分片源边界。 */
    public SimAdEventsJob(AdSimulationWriter writer, AdSimulationCatalogMapper catalog) {
        this.writer = writer;
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "sim-ad-events";
    }

    @Override
    public void run(ApplicationArguments args) {
        int requests = intArg(args, "requests", 20000);
        long seed = intArg(args, "seed", 7);
        int days = intArg(args, "days", 14);
        double meanDelayDays = dblArg(args, "conv-delay-days", 3.0);
        // 注:用裸 MAX(ad_id)(不裹 COALESCE)——ShardingSphere 的聚合归并能识别裸聚合、跨分片合并成单值;
        // COALESCE(MAX(...),0) 会被当成普通投影 → 每分片各返一行 → queryForObject 报 "actual 2"。空表则 null。
        Long maxAdId = catalog.maxAdId();
        if (maxAdId == null || maxAdId == 0) {
            log.warn("无广告,先跑 --job=seed-ads");
            return;
        }
        Random rnd = new Random(seed);

        // DCO:每广告的创意 id 列表(供按创意归因曝光,让创意级 CTR 可学)
        Map<Long, long[]> creativesByAd = loadCreatives();

        long now = System.currentTimeMillis();
        long windowMs = (long) days * 86_400_000L;
        double meanDelayMs = meanDelayDays * 86_400_000L;

        List<AdSimulationMapper.Impression> impressions = new ArrayList<>();
        List<AdSimulationMapper.Action> clicks = new ArrayList<>();
        List<AdSimulationMapper.Action> conversions = new ArrayList<>();
        long observedConv = 0;                                    // 转化时刻 <= now 的(已到达)
        for (int i = 0; i < requests; i++) {
            String reqId = "sim-" + UUID.randomUUID();
            long userId = 1 + rnd.nextInt(600);
            long adId = 1 + (long) (rnd.nextDouble() * maxAdId);
            int position = 1 + rnd.nextInt(3);
            double trueCtr = 0.02 + 0.38 * rnd.nextDouble();
            double pctr = Math.pow(trueCtr, 0.7);                 // 系统性高估
            double charged = round4(0.1 + rnd.nextDouble());      // 模拟 GSP 价(元)
            long eventTs = now - (long) (rnd.nextDouble() * windowMs);  // 曝光/点击时刻铺在窗口内
            // DCO:本次曝光随机摊到该广告的一套创意,创意自带 CTR 因子(有的创意系统性更好,让 UCB 可学)
            long creativeId = pickCreative(creativesByAd.get(adId), rnd);
            double creativeFactor = creativeCtrFactor(creativeId);
            double effCtr = Math.min(0.95, trueCtr * creativeFactor);
            impressions.add(new AdSimulationMapper.Impression(reqId, userId, adId, position, pctr, charged,
                    new Timestamp(eventTs), creativeId > 0 ? creativeId : null));
            if (rnd.nextDouble() < effCtr) {
                clicks.add(new AdSimulationMapper.Action(reqId, userId, adId, new Timestamp(eventTs)));
                // 点击后按真实 CVR 转化(教学用,给 oCPC 反馈控制 ad-ocpc 喂数据)
                double trueCvr = 0.08 + 0.22 * rnd.nextDouble();
                if (rnd.nextDouble() < trueCvr) {
                    // 指数延迟:delay = -mean·ln(U);转化时刻 = 点击时刻 + 延迟(可能晚于 now = 尚未到达)
                    long delayMs = (long) (-meanDelayMs * Math.log(1.0 - rnd.nextDouble()));
                    long convTs = eventTs + delayMs;
                    conversions.add(new AdSimulationMapper.Action(reqId, userId, adId, new Timestamp(convTs)));
                    if (convTs <= now) {
                        observedConv++;
                    }
                }
            }
        }

        writer.impressions(impressions);
        writer.clicks(clicks);
        writer.conversions(conversions);

        log.info("sim-ad-events 完成(教学用):曝光 {} / 点击 {} / 转化 {}(其中已到达 {},尚未到达 {} = 延迟删失)。"
                        + "经验 CTR≈{}。窗口 {} 天、平均转化延迟 {} 天。"
                        + "可跑 --job=ad-delay(拟合延迟分布)→ --job=ad-ocpc(纠偏调价)、--job=ad-calibrate(校准 pCTR)",
                impressions.size(), clicks.size(), conversions.size(),
                observedConv, conversions.size() - observedConv,
                round4((double) clicks.size() / impressions.size()), days, meanDelayDays);
    }

    /** 载入每广告的创意 id 列表(DCO 归因用);无 ad_creative 表/数据 → 空 map,creative_id 落 NULL。 */
    private Map<Long, long[]> loadCreatives() {
        Map<Long, List<Long>> tmp = new HashMap<>();
        try {
            catalog.scanCreatives(context -> {
                var row = context.getResultObject();
                tmp.computeIfAbsent(row.adId(), k -> new ArrayList<>()).add(row.creativeId());
            });
        } catch (Exception e) {
            log.warn("无 ad_creative(先跑 --job=seed-ads 造创意),曝光不归因创意: {}", e.getMessage());
            return Map.of();
        }
        Map<Long, long[]> out = new HashMap<>(tmp.size());
        tmp.forEach((ad, list) -> {
            long[] arr = new long[list.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = list.get(i);
            }
            out.put(ad, arr);
        });
        return out;
    }

    /** 随机摊到该广告的一套创意;无创意 → 0(落 NULL)。 */
    private static long pickCreative(long[] creatives, Random rnd) {
        if (creatives == null || creatives.length == 0) {
            return 0;
        }
        return creatives[rnd.nextInt(creatives.length)];
    }

    /** 创意 CTR 因子 ∈[0.6,1.4](由 creativeId 确定性散列,让有的创意系统性更好,DCO 才有得学)。 */
    private static double creativeCtrFactor(long creativeId) {
        if (creativeId <= 0) {
            return 1.0;
        }
        double frac = ((creativeId * 2654435761L) >>> 11) % 1000 / 1000.0;  // 确定性 [0,1)
        return 0.6 + 0.8 * frac;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static double dblArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }
}
