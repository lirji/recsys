package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.recsys.offline.persistence.mapper.primary.AdQualityMapper;
import com.recsys.offline.persistence.model.AdEventSource;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 作业 ad-quality:**质量度精细化(docs/05 §7 M7)**。把进 eCPM 的 {@code quality}
 * 从随机基线({@code ad.quality_score},M4 占位)换成<b>可解释、数据驱动</b>的分数,写 Redis
 * {@code ad:quality:{adId}},在线 {@link com.recsys.ad.QualityScoreService} 查表(缺失退随机基线)。
 *
 * <p><b>解决什么</b>:{@code eCPM = bid × pCTR_calib × quality × relevance} 里 quality 是随机数,
 * 等于给"谁出现 + 收多少钱"注入噪声。本作业用三个可回溯的历史信号融合成 quality:
 * <ul>
 *   <li><b>relevance</b>:该广告历史 {@code ad_event.relevance} 均值(query↔ad 相关性);</li>
 *   <li><b>CTR 质量</b>:经验点击率(贝叶斯收缩向大盘 CTR,低量广告 ≈ 大盘,不被小样本带偏);</li>
 *   <li><b>CVR/落地页质量代理</b>:点击后转化率(收缩向大盘 CVR)——转化好≈落地页/承接好。</li>
 * </ul>
 * 各因子先除以大盘均值归一到"围绕 1.0 的乘子"(平均广告 → 各因子 ≈1)、逐项 clamp,再加权融合并 clamp
 * 到 {@code [qMin,qMax]}。于是<b>平均广告 quality≈1.0(等同旧随机基线的中位)、好广告>1、差广告<1</b>。
 *
 * <p><b>可审计</b>(守 §8 #3 红线):质量度直接影响排序与计费,绝不能是黑箱——每个广告的三因子原始值 +
 * 归一值 + 最终分写出 CSV {@code eval/ad-quality-<ts>.csv} 供对账;Redis 只存最终乘子供在线快查。
 *
 * <p><b>与 EE 探索的关系</b>:本质量度是<b>长期</b>的性能加权(进排序也进计费,因为它源自校准后的历史聚合,
 * 不是未校准的单次概率);{@link com.recsys.ad.ExplorationService} 的 UCB 是<b>临时</b>抬权(只进排序),
 * 二者在 Ad Rank 上相乘、各司其职,不冲突。
 *
 * <p>参数:--days(窗口,默认 30;<=0 全部)、--min-impr(入选最少曝光,默认 20)、
 * --w-rel/--w-ctr/--w-cvr(三因子权重,默认 0.3/0.4/0.3)、--q-min/--q-max(最终 clamp,默认 0.5/1.5)、
 * --comp-clamp(单因子归一上/下限幅度,默认 0.5 → 各因子 ∈[0.5,1.5])、
 * --prior-impr(CTR 收缩先验强度,默认 200 次曝光)、--prior-click(CVR 收缩先验强度,默认 20 次点击)。
 */
@Component
public class QualityScoreJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(QualityScoreJob.class);
    private static final String OUT_DIR = "eval";
    private static final double QUALITY_SCALE = 10_000.0;

    private final AdQualityMapper mapper;
    private final StringRedisTemplate redis;

    /** 复用原主源Mapper，报表算法和发布边界保持在作业内。 */
    public QualityScoreJob(AdQualityMapper mapper, StringRedisTemplate redis) {
        this.mapper = mapper;
        this.redis = redis;
    }

    /** 保留既有离线命令名。 */
    @Override
    public String name() {
        return "ad-quality";
    }

    /** 保持逐广告发布顺序；任一失败须说明已有结果可能生效，不能伪称整批成功。 */
    @Override
    public void run(ApplicationArguments args) {
        AdEventSource source = AdEventQuery.source(args);
        int days = intArg(args, "days", 30);
        int minImpr = intArg(args, "min-impr", 20);
        double wRel = dblArg(args, "w-rel", 0.3);
        double wCtr = dblArg(args, "w-ctr", 0.4);
        double wCvr = dblArg(args, "w-cvr", 0.3);
        double qMin = dblArg(args, "q-min", 0.5);
        double qMax = dblArg(args, "q-max", 1.5);
        double compClamp = dblArg(args, "comp-clamp", 0.5);
        double priorImpr = dblArg(args, "prior-impr", 200);
        double priorClick = dblArg(args, "prior-click", 20);
        double wSum = wRel + wCtr + wCvr;
        if (minImpr < 0 || priorImpr < 0 || priorClick < 0 || wRel < 0 || wCtr < 0 || wCvr < 0
                || wSum <= 0 || !Double.isFinite(wSum) || qMin < 0 || qMax < qMin || compClamp < 0) {
            throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        }

        // 每广告聚合:曝光数 + 相关性和、点击数、转化数(点击/转化按 request 去重)
        Map<Long, double[]> agg = new HashMap<>();   // adId → [impr, relSum, clicks, conv]
        mapper.scanImpressions(source, days, context -> {
            var row = context.getResultObject();
            double[] a = agg.computeIfAbsent(row.adId(), k -> new double[4]);
            a[0] = row.count();
            a[1] = row.relevanceSum();
        });
        mapper.scanClicks(source, days, context -> {
            var row = context.getResultObject();
            double[] a = agg.get(row.adId());
            if (a != null) a[2] = row.count();
        });
        mapper.scanConversions(source, days, context -> {
            var row = context.getResultObject();
            double[] a = agg.get(row.adId());
            if (a != null) a[3] = row.count();
        });

        if (agg.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_NO_AD_QUALITY_INPUT);
        }

        // 大盘均值(归一基准):popRel=曝光加权相关性、popCtr=总点击/总曝光、popCvr=总转化/总点击
        double totImpr = 0, totRel = 0, totClick = 0, totConv = 0;
        for (double[] a : agg.values()) {
            totImpr += a[0];
            totRel += a[1];
            totClick += a[2];
            totConv += a[3];
        }
        double popRel = totImpr > 0 ? totRel / totImpr : 0;
        double popCtr = totImpr > 0 ? totClick / totImpr : 0;
        double popCvr = totClick > 0 ? totConv / totClick : 0;

        // 大盘无效不能因后续clamp或round悄悄转成中性值/零；尚未发布任何广告。
        round4(popRel);
        round4(popCtr);
        round4(popCvr);

        Path outFile = Path.of(OUT_DIR,
                "ad-quality-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");
        int written = 0, skipped = 0;
        try {
            Files.createDirectories(outFile.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
                w.write("ad_id,impr,clicks,conv,rel,ctr_shrunk,cvr_shrunk,relN,ctrN,cvrN,quality");
                w.newLine();
                for (Map.Entry<Long, double[]> e : agg.entrySet()) {
                    double[] a = e.getValue();
                    double impr = a[0], relSum = a[1], clicks = a[2], conv = a[3];
                    if (impr < minImpr) {
                        skipped++;
                        continue;
                    }
                    double rel = impr > 0 ? relSum / impr : 0;
                    // 贝叶斯收缩:低量广告向大盘回归,避免小样本极端值
                    double ctrShrunk = QualityScore.shrink(clicks, impr, popCtr, priorImpr);
                    double cvrShrunk = QualityScore.shrink(conv, clicks, popCvr, priorClick);
                    // 归一到围绕 1.0 的乘子(大盘基准缺失则取中性 1.0),逐项 clamp
                    double relN = QualityScore.norm(rel, popRel, compClamp);
                    double ctrN = QualityScore.norm(ctrShrunk, popCtr, compClamp);
                    double cvrN = QualityScore.norm(cvrShrunk, popCvr, compClamp);
                    double quality = QualityScore.fuse(relN, ctrN, cvrN, wRel, wCtr, wCvr, qMin, qMax);
                    // 先校验整行再发布当前广告；零先验且零点击产生的NaN不能被Math.round伪装成0。
                    String csvRow = e.getKey() + "," + (long) impr + "," + (long) clicks + "," + (long) conv + ","
                            + round4(rel) + "," + round4(ctrShrunk) + "," + round4(cvrShrunk) + ","
                            + round4(relN) + "," + round4(ctrN) + "," + round4(cvrN) + "," + round4(quality);
                    redis.opsForValue().set(RedisKeys.adQuality(e.getKey()), String.valueOf(round4(quality)));
                    written++;
                    w.write(csvRow);
                    w.newLine();
                }
            }
        } catch (PublicErrorException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new PublicErrorException(ErrorCode.JOB_AD_QUALITY_PUBLICATION_FAILED, failure);
        }
        if (written == 0) throw new PublicErrorException(ErrorCode.JOB_NO_AD_QUALITY_OUTPUT);

        log.info("ad-quality 完成:候选广告 {},写入 {}(曝光<{} 跳过 {})。大盘 rel={} ctr={} cvr={}。"
                        + "权重 rel/ctr/cvr={}/{}/{},clamp [{},{}]。明细 CSV: {}",
                agg.size(), written, minImpr, skipped,
                round4(popRel), round4(popCtr), round4(popCvr), wRel, wCtr, wCvr, qMin, qMax,
                outFile.toAbsolutePath());
    }

    private static double round4(double v) {
        double scaled = v * QUALITY_SCALE;
        if (!Double.isFinite(scaled) || scaled < Long.MIN_VALUE || scaled >= (double) Long.MAX_VALUE) {
            throw new PublicErrorException(ErrorCode.JOB_AD_QUALITY_INPUT_INVALID);
        }
        return Math.round(scaled) / QUALITY_SCALE;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return JobArguments.integer(a, k, def);
    }

    private static double dblArg(ApplicationArguments a, String k, double def) {
        return JobArguments.decimal(a, k, def);
    }
}
