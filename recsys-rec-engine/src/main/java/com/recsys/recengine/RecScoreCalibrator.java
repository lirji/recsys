package com.recsys.recengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 推荐精排分数校准:保序回归(isotonic)查表 + 线性插值。
 *
 * <p><b>为什么</b>:isotonic 是单调变换,单策略内不改排序 —— 但它把 rank 原始分映射成可比的
 * 真实正反馈率(概率),使融合分 {@code recallWeight·rNorm + rankWeight·calibratedScore} 的
 * 两项量纲一致、可解释、可设阈值(算法审查指出的「融合分量纲不可解释」问题)。
 *
 * <p>离线 {@code RecCalibrateJob} 重跑 recall→rank 于时间切分留出集,拟合 (rankScore → 经验正反馈率)
 * 的单调分段函数,写 Redis {@code rec:calib:{model}}(JSON {@code {"x":[...],"y":[...]}})。
 * 本类按 model 缓存并每 {@link #REFRESH_MS} 刷新;表缺失/解析失败 → identity(原样返回),安全降级。
 *
 * <p>与广告的 {@code IsotonicCalibrator} 同属保序回归查表模式,但独立命名空间与生命周期,故不共用。
 */
@Component
public class RecScoreCalibrator {

    private static final Logger log = LoggerFactory.getLogger(RecScoreCalibrator.class);
    private static final long REFRESH_MS = 60_000;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public RecScoreCalibrator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 校准原始 rank 分;无表则原样返回。 */
    public double calibrate(double rawScore, String model) {
        Table t = table(model);
        return t == null ? rawScore : t.interpolate(rawScore);
    }

    /** 是否已有该 model 的校准表(供上层观测/日志)。 */
    public boolean isCalibrated(String model) {
        return table(model) != null;
    }

    private Table table(String model) {
        Cached c = cache.get(model);
        long now = System.currentTimeMillis();
        if (c != null && now - c.loadedAt < REFRESH_MS) {
            return c.table;   // 可能为 null(已知无表,避免反复打 Redis)
        }
        Table loaded = load(model);
        cache.put(model, new Cached(loaded, now));
        return loaded;
    }

    private Table load(String model) {
        try {
            String json = redis.opsForValue().get(RedisKeys.recCalib(model));
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonNode node = mapper.readTree(json);
            JsonNode xs = node.get("x");
            JsonNode ys = node.get("y");
            if (xs == null || ys == null || xs.size() == 0 || xs.size() != ys.size()) {
                return null;
            }
            double[] x = new double[xs.size()];
            double[] y = new double[ys.size()];
            for (int i = 0; i < x.length; i++) {
                x[i] = xs.get(i).asDouble();
                y[i] = ys.get(i).asDouble();
            }
            log.debug("加载推荐校准表 model={} 分段点={}", model, x.length);
            return new Table(x, y);
        } catch (Exception e) {
            log.debug("加载推荐校准表失败 model={}: {}", model, e.getMessage());
            return null;
        }
    }

    private record Cached(Table table, long loadedAt) {
    }

    /** 单调递增分段点 + 线性插值(定义域外做端点钳制)。 */
    private record Table(double[] x, double[] y) {
        double interpolate(double p) {
            if (p <= x[0]) {
                return y[0];
            }
            if (p >= x[x.length - 1]) {
                return y[y.length - 1];
            }
            int lo = 0, hi = x.length - 1;
            while (lo + 1 < hi) {
                int mid = (lo + hi) >>> 1;
                if (x[mid] <= p) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            double dx = x[hi] - x[lo];
            double w = dx <= 0 ? 0 : (p - x[lo]) / dx;
            return y[lo] + w * (y[hi] - y[lo]);
        }
    }
}
