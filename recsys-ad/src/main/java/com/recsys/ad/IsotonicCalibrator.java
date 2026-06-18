package com.recsys.ad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.Calibrator;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * pCTR 保序回归(isotonic)校准:在线查表 + 线性插值。
 *
 * <p>离线 {@code AdCalibrateJob} 用 {@code ad_event}(预估 pctr vs 实际点击率)拟合单调递增的分段函数,
 * 写 Redis {@code ad:calib:{model}}(JSON:{@code {"x":[...],"y":[...]}},x 升序=原始 pctr 分段点,
 * y=对应校准后概率)。本类按 model 缓存该表,每 {@link #REFRESH_MS} 刷新一次。
 *
 * <p><b>降级</b>(docs/05 §9.3/§9.4 红线):表缺失/解析失败 → identity(原样返回),
 * {@link #isCalibrated} 返回 false,由上层决定是否仍允许进计费(本脚手架默认放行并日志标记)。
 */
@Component
public class IsotonicCalibrator implements Calibrator {

    private static final Logger log = LoggerFactory.getLogger(IsotonicCalibrator.class);
    private static final long REFRESH_MS = 60_000;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, CachedTable> cache = new ConcurrentHashMap<>();

    public IsotonicCalibrator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public double calibrate(double rawProb, String model) {
        CalibTable t = table(model);
        if (t == null) {
            return rawProb;
        }
        return t.interpolate(rawProb);
    }

    @Override
    public boolean isCalibrated(String model) {
        return table(model) != null;
    }

    private CalibTable table(String model) {
        CachedTable cached = cache.get(model);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt < REFRESH_MS) {
            return cached.table; // 可能为 null(已知无表,避免反复打 Redis)
        }
        CalibTable loaded = load(model);
        cache.put(model, new CachedTable(loaded, now));
        return loaded;
    }

    private CalibTable load(String model) {
        try {
            String json = redis.opsForValue().get(RedisKeys.adCalib(model));
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
            log.debug("加载校准表 model={} 分段点={}", model, x.length);
            return new CalibTable(x, y);
        } catch (Exception e) {
            log.debug("加载校准表失败 model={}: {}", model, e.getMessage());
            return null;
        }
    }

    private record CachedTable(CalibTable table, long loadedAt) {
    }

    /** 单调递增分段点 + 线性插值。 */
    private record CalibTable(double[] x, double[] y) {
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
