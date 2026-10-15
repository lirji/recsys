package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import com.recsys.offline.persistence.mapper.primary.OcpcFeedbackMapper;
import com.recsys.offline.persistence.mapper.sharded.OcpcTargetMapper;
import com.recsys.offline.persistence.model.AdEventSource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 作业 ad-ocpc:用 {@code ad_event} 反馈控制拟合每个广告主的 oCPC 出价调节系数 k,
 * 写 Redis {@code ad:ocpc:{advertiserId}}(docs/05 §6,M6)。在线 {@link com.recsys.ad.OcpcBidder}
 * 出价 {@code bid = targetCpa × pCVR × k};本作业让"实际 CPA"收敛到广告主设的"目标 CPA"。
 *
 * <p><b>反馈控制</b>(对每个有 OCPC 广告的广告主):
 * <pre>
 *   实际 CPA = 窗口内花费 / 转化数
 *   k_new = clamp( k_old × (目标 CPA / 实际 CPA)^alpha , kMin, kMax )
 * </pre>
 * 实际 CPA 高于目标(超成本)→ 比值 &lt;1 → 调低 k → 降出价/降量;反之抬高。alpha 为阻尼,
 * 防一步过冲。无转化的广告主无法估计 → 保持原 k(宁可不动也不乱调)。
 *
 * <p>花费口径(CPC 计费):点击(CLICK)按其曝光(IMPRESSION)的 GSP 价 {@code charged_price} 累计,
 * 与在线 {@code PacingService.charge} 一致。花费与转化均以<b>点击时刻</b>落在窗口内为口径,保证 CPA 同分母。
 *
 * <p><b>延迟转化纠偏(docs/05 §6,M6)</b>:近窗口的点击尚未来得及转化,直接 {@code COUNT(转化)} 会
 * 系统性偏低 → CPA 高估 → k 被过度调低、错杀好广告主。开启 {@code --delay-correct}(默认)时,读
 * {@link DelayModelJob} 拟合的延迟速率 λ({@link RedisKeys#AD_DELAY_LAMBDA}),对每个<b>已到达</b>转化按
 * 其点击的 elapsed 做 Horvitz–Thompson 加权 {@code 1/c(elapsed)}(完成曲线 {@code c(e)=1−e^(−λe)}),
 * 把"还在路上"的转化补回来,得无偏的"终值转化数"再算 CPA。λ 缺失或 {@code --delay-correct=false}
 * → 退化为原始计数(不纠偏)。{@code --min-completion} 给权重设上限(防极近点击权重爆炸)。
 *
 * <p>参数:--days(默认 7,只看近 N 天;<=0 保留 epoch 下界)、--alpha(默认 0.5)、
 * --min(默认 0.2)、--max(默认 5.0)、--delay-correct(默认 true)、--min-completion(默认 0.05)。
 */
@Component
public class OcpcCalibrateJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(OcpcCalibrateJob.class);
    private static final double INITIAL_K = 1.0;
    private static final double COEFFICIENT_SCALE = 10_000.0;

    private final OcpcFeedbackMapper feedback;
    private final OcpcTargetMapper targets;
    private final StringRedisTemplate redis;

    /** 主源事件与逻辑分片广告通过各自Mapper读取，不能跨库JOIN或新增池。 */
    public OcpcCalibrateJob(OcpcFeedbackMapper feedback, OcpcTargetMapper targets, StringRedisTemplate redis) {
        this.feedback = feedback;
        this.targets = targets;
        this.redis = redis;
    }

    /** 保留既有离线校准命令名。 */
    @Override
    public String name() {
        return "ad-ocpc";
    }

    /** 逐广告主发布可能部分生效；故障不能被当作模型缺失或全批完成。 */
    @Override
    public void run(ApplicationArguments args) {
        try {
            calibrate(args);
        } catch (PublicErrorException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // ResultHandler 的受控数据校验会被 MyBatis 包装；保留原目录码而非误报依赖故障。
            if (failure instanceof DataAccessException access
                    && access.getMostSpecificCause() instanceof PublicErrorException controlled) {
                throw controlled;
            }
            throw new PublicErrorException(ErrorCode.JOB_OCPC_FAILED, failure);
        }
    }

    private void calibrate(ApplicationArguments args) {
        AdEventSource source = AdEventQuery.source(args);
        int days = JobArguments.integer(args, "days", 7);
        double alpha = JobArguments.decimal(args, "alpha", 0.5);
        double kMin = JobArguments.decimal(args, "min", 0.2);
        double kMax = JobArguments.decimal(args, "max", 5.0);
        // 原命令支持裸开关；有值时必须严格解析，不能把拼错当关闭。
        boolean delayCorrect = args.containsOption("delay-correct") && args.getOptionValues("delay-correct").isEmpty()
                || JobArguments.bool(args, "delay-correct", true);
        double minCompletion = JobArguments.decimal(args, "min-completion", 0.05);

        if (alpha < 0 || kMin <= 0 || kMax < kMin || minCompletion <= 0 || minCompletion > 1) {
            throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        }

        // 延迟转化模型:λ(1/天)。开启纠偏且 Redis 有 λ 时,对转化做 Horvitz–Thompson 加权;否则原始计数。
        double lambda = delayCorrect ? readLambda() : 0.0;
        boolean correcting = lambda > 0;

        // 分库:ad 在分片库、ad_event 在主库,不能跨源 JOIN。先从分片库取 OCPC 广告的 目标CPA / ad→广告主映射,
        // 再在主库按 ad_id ∈ OCPC广告 聚合 ad_event,最后在 Java 折算到广告主(口径与原 JOIN 版完全一致)。

        // 1. OCPC 广告:每个广告主的目标 CPA(均值)+ ad_id→advertiser_id 映射(分片库)
        Map<Long, Double> targetCpa = new HashMap<>();
        targets.scanTargets(context -> {
            var row = context.getResultObject();
            requirePositive(row.targetCpa());
            targetCpa.put(row.advertiserId(), row.targetCpa());
        });
        if (targetCpa.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_NO_OCPC_TARGET);
        }
        Map<Long, Long> adToAdv = new HashMap<>();
        targets.scanOwners(context -> {
            var row = context.getResultObject();
            adToAdv.put(row.adId(), row.advertiserId());
        });
        Long[] ocpcAdIds = adToAdv.keySet().toArray(new Long[0]);

        // 2. 窗口内花费(主库 ad_event:点击归因到曝光 GSP 价),按 ad_id 聚合 → 折算到广告主
        Map<Long, Double> spend = new HashMap<>();
        feedback.scanSpend(source, days, ocpcAdIds, context -> {
            var row = context.getResultObject();
            Long adv = adToAdv.get(row.adId());
            if (adv != null) {
                requireNonnegative(row.amount());
                spend.merge(adv, row.amount(), Double::sum);
            }
        });

        // 3. 窗口内转化(主库 ad_event)。逐条取 (ad_id, 该点击的 elapsed 天数) → 映射到广告主,按 DelayModel.htWeight
        //    累加(纠偏公式收敛在 DelayModel,唯一真相)。correcting=false 时权重恒 1(=原始计数)。
        Map<Long, Double> conversions = new HashMap<>();   // 终值(可能为小数)
        Map<Long, Long> rawConv = new HashMap<>();         // 已观测原始计数
        feedback.scanConversions(source, days, ocpcAdIds, context -> {
            var row = context.getResultObject();
            Long adv = adToAdv.get(row.adId());
            if (adv == null) {
                return;
            }
            if (correcting && !Double.isFinite(row.elapsedDays())) {
                throw new PublicErrorException(ErrorCode.JOB_OCPC_INVALID_DATA);
            }
            double w = correcting ? DelayModel.htWeight(lambda, row.elapsedDays(), minCompletion) : 1.0;
            conversions.merge(adv, w, Double::sum);
            rawConv.merge(adv, 1L, Long::sum);
        });

        int updated = 0, held = 0;
        for (Map.Entry<Long, Double> e : targetCpa.entrySet()) {
            long adv = e.getKey();
            double target = e.getValue();
            double kOld = readK(adv);
            double conv = conversions.getOrDefault(adv, 0.0);   // 终值转化数(纠偏后)
            long raw = rawConv.getOrDefault(adv, 0L);           // 已观测原始转化数
            double cost = spend.getOrDefault(adv, 0.0);
            requireNonnegative(conv);
            requireNonnegative(cost);
            if (conv <= 0 || cost <= 0) {
                held++;
                log.debug("广告主 {} 无转化/花费,保持 k={}", adv, round4(kOld));
                continue;
            }
            double actualCpa = cost / conv;
            requirePositive(actualCpa);
            double adjusted = kOld * Math.pow(target / actualCpa, alpha);
            requirePositive(adjusted);
            double kNew = clamp(adjusted, kMin, kMax);
            redis.opsForValue().set(RedisKeys.adOcpc(adv), String.valueOf(publishedCoefficient(kNew)));
            updated++;
            log.info("广告主 {} 目标CPA={} 实际CPA={} (花费{}/转化{}{}) k {}→{}",
                    adv, round2(target), round2(actualCpa), round2(cost),
                    correcting ? round2(conv) : (long) conv,
                    correcting ? "[原始" + raw + "]" : "", round4(kOld), round4(kNew));
        }
        log.info("ad-ocpc 完成:OCPC 广告主 {} 个,更新系数 {} 个,无转化保持 {} 个(窗口={}天,alpha={},"
                        + "延迟纠偏={})",
                targetCpa.size(), updated, held, days, alpha,
                correcting ? "λ=" + round6(lambda) + "(1/天)" : (delayCorrect ? "λ缺失→关闭" : "关闭"));
    }

    /** 仅真正缺失使用初始系数；坏值或读取失败不得触发覆盖旧模型。 */
    private double readK(long advertiserId) {
        String value = redis.opsForValue().get(RedisKeys.adOcpc(advertiserId));
        return value == null ? INITIAL_K : storedPositive(value);
    }

    /** 延迟模型缺失允许原始计数降级，故障和损坏须失败且保留旧系数。 */
    private double readLambda() {
        String value = redis.opsForValue().get(RedisKeys.AD_DELAY_LAMBDA);
        if (value == null) {
            ErrorCode code = ErrorCode.JOB_OCPC_DELAY_MISSING;
            log.warn("[{}] {}", code.code(), code.message());
            return 0.0;
        }
        return storedPositive(value);
    }

    private static double storedPositive(String value) {
        try {
            double parsed = Double.parseDouble(value.trim());
            requirePositive(parsed);
            return parsed;
        } catch (NumberFormatException failure) {
            throw new PublicErrorException(ErrorCode.JOB_OCPC_INVALID_DATA, failure);
        }
    }

    private static void requireNonnegative(double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new PublicErrorException(ErrorCode.JOB_OCPC_INVALID_DATA);
        }
    }

    private static void requirePositive(double value) {
        requireNonnegative(value);
        if (value == 0) throw new PublicErrorException(ErrorCode.JOB_OCPC_INVALID_DATA);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double publishedCoefficient(double v) {
        // Math.round 会把NaN变0、溢出变long饱和值；发布前显式拒绝不可表示的系数。
        double scaled = v * COEFFICIENT_SCALE;
        if (!Double.isFinite(scaled) || scaled >= Long.MAX_VALUE || Math.round(scaled) <= 0) {
            throw new PublicErrorException(ErrorCode.JOB_OCPC_INVALID_DATA);
        }
        return Math.round(scaled) / COEFFICIENT_SCALE;
    }

    private static double round4(double v) {
        return Math.round(v * COEFFICIENT_SCALE) / COEFFICIENT_SCALE;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000) / 1_000_000.0;
    }

}
