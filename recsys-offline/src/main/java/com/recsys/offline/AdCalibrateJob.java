package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.recsys.offline.persistence.mapper.primary.AdCalibrationMapper;
import com.recsys.offline.persistence.model.AdEventSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.StringJoiner;

/**
 * 作业 ad-calibrate:用 {@code ad_event}(预估 pctr vs 实际点击)拟合 pCTR 保序回归校准表,
 * 写 Redis {@code ad:calib:{model}}(JSON:{@code {"x":[...],"y":[...]}}),在线 {@code IsotonicCalibrator}
 * 查表线性插值(docs/05 §4.4)。红线:未校准的 pCTR 不得进计费。
 *
 * <p>步骤:取所有 IMPRESSION 的 (pctr, 是否点击) → 按 pctr 等频分箱 → 每箱经验 CTR →
 * PAVA(Pool Adjacent Violators)强制单调递增 → x=箱内平均 pctr,y=校准后 CTR。
 *
 * <p>参数:--model(默认 deepfm,与在线 recsys.ad.calib-model 对齐)、--bins(默认 20)。
 */
@Component
public class AdCalibrateJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(AdCalibrateJob.class);

    private final AdCalibrationMapper mapper;
    private final StringRedisTemplate redis;

    /** Mapper沿原主库会话读取，保持Redis发布的先后边界。 */
    public AdCalibrateJob(AdCalibrationMapper mapper, StringRedisTemplate redis) {
        this.mapper = mapper;
        this.redis = redis;
    }

    /** 保留既有离线命令名。 */
    @Override
    public String name() {
        return "ad-calibrate";
    }

    /** 来源按单次运行传递，输入查询与原拟合/发布算法各自负责。 */
    @Override
    public void run(ApplicationArguments args) {
        AdEventSource source = AdEventQuery.source(args);
        String model = strArg(args, "model", "deepfm");
        int bins = intArg(args, "bins", 20);

        List<AdCalibrationMapper.Sample> rows = mapper.load(source);

        if (rows.size() < bins * 5L) {
            log.warn("样本太少({} 条),先跑 --job=sim-ad-events(或灌真实曝光/点击)", rows.size());
            return;
        }

        // 等频分箱
        int n = rows.size();
        double[] x = new double[bins];
        double[] y = new double[bins];
        double[] w = new double[bins];
        for (int b = 0; b < bins; b++) {
            int from = (int) ((long) b * n / bins);
            int to = (int) ((long) (b + 1) * n / bins);
            double sumP = 0, sumC = 0;
            for (int i = from; i < to; i++) {
                sumP += rows.get(i).prediction();
                sumC += rows.get(i).clicked();
            }
            int cnt = to - from;
            x[b] = sumP / cnt;
            y[b] = sumC / cnt;
            w[b] = cnt;
        }

        pava(y, w); // 强制单调递增

        String json = toJson(x, y);
        redis.opsForValue().set(RedisKeys.adCalib(model), json);
        log.info("ad-calibrate 完成 model={} 样本={} 分箱={};校准表已写 {}。示例: pctr {}→{} … {}→{}",
                model, n, bins, RedisKeys.adCalib(model),
                round4(x[0]), round4(y[0]), round4(x[bins - 1]), round4(y[bins - 1]));
    }

    /** Pool Adjacent Violators:加权最小二乘下使 y 非递减(原地修改 y)。 */
    private static void pava(double[] y, double[] w) {
        int n = y.length;
        double[] val = new double[n];
        double[] wt = new double[n];
        int[] len = new int[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            val[k] = y[i];
            wt[k] = w[i];
            len[k] = 1;
            while (k > 0 && val[k - 1] > val[k]) {
                double mergedW = wt[k - 1] + wt[k];
                val[k - 1] = (val[k - 1] * wt[k - 1] + val[k] * wt[k]) / mergedW;
                wt[k - 1] = mergedW;
                len[k - 1] += len[k];
                k--;
            }
            k++;
        }
        int idx = 0;
        for (int b = 0; b < k; b++) {
            for (int j = 0; j < len[b]; j++) {
                y[idx++] = val[b];
            }
        }
    }

    private static String toJson(double[] x, double[] y) {
        StringJoiner xs = new StringJoiner(",", "[", "]");
        StringJoiner ys = new StringJoiner(",", "[", "]");
        for (int i = 0; i < x.length; i++) {
            xs.add(String.valueOf(round6(x[i])));
            ys.add(String.valueOf(round6(y[i])));
        }
        return "{\"x\":" + xs + ",\"y\":" + ys + "}";
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000) / 1_000_000.0;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static String strArg(ApplicationArguments a, String k, String def) {
        return a.containsOption(k) ? a.getOptionValues(k).get(0) : def;
    }
}
