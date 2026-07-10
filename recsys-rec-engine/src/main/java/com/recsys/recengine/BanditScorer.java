package com.recsys.recengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.bandit.BanditModelDto;
import com.recsys.common.bandit.LinUcbModel;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * R7 全量 contextual bandit 在线打分:读离线 {@code bandit-stats} 写的 {@link RedisKeys#BANDIT_MODEL}
 * (LinUCB 充分统计 A/b),预计算 {@code A⁻¹}、{@code θ̂},在融合阶段给每条候选算探索加成。
 *
 * <p>结构镜像 {@link FtrlScorer}:{@link #REFRESH_MS} 缓存刷新;缺 key/解析失败/矩阵奇异 →
 * {@link #isReady()}=false,打分 0(不影响融合,零风险)。
 *
 * <p>用法:融合前 {@code var s = forRequest(mode, alpha)}(Thompson 模式**每请求采样一次** θ̃),
 * 再对每条候选 {@code s.score(featureSnapshot)}。上下文 x 按模型内 {@code order} 从排序特征快照按名取
 * (与离线 {@code FeatureAssembler} 同源,逐位一致)。
 */
@Component
public class BanditScorer {

    private static final Logger log = LoggerFactory.getLogger(BanditScorer.class);
    private static final long REFRESH_MS = 60_000;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile Loaded loaded;
    private volatile long loadedAt = -1;

    public BanditScorer(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isReady() {
        return model() != null;
    }

    /**
     * 构造一次请求的打分会话。Thompson 模式在此**采样一次** θ̃(整批候选共用同一后验样本 = 正确的
     * 每决策一采样);LinUCB 模式持点估计 + A⁻¹ 出置信宽度。模型未就绪 → 返回恒 0 的空会话。
     *
     * @param mode  linucb | thompson(其它值按 linucb)
     * @param alpha 置信宽度 / 采样标准差倍数
     */
    public Session forRequest(String mode, double alpha) {
        Loaded m = model();
        if (m == null) {
            return new Session(null, null, null, 0.0, true);
        }
        boolean thompson = "thompson".equalsIgnoreCase(mode);
        if (thompson) {
            double[] tilde = LinUcbModel.sampleTheta(m.thetaHat, m.aInv, alpha, ThreadLocalRandom.current());
            return new Session(m.order, tilde, null, alpha, false);
        }
        return new Session(m.order, m.thetaHat, m.aInv, alpha, true);
    }

    private Loaded model() {
        long now = System.currentTimeMillis();
        if (loaded != null && now - loadedAt < REFRESH_MS) {
            return loaded;
        }
        Loaded l = load();
        loaded = l;
        loadedAt = now;
        return l;
    }

    private Loaded load() {
        try {
            String json = redis.opsForValue().get(RedisKeys.BANDIT_MODEL);
            if (json == null || json.isBlank()) {
                return null;
            }
            BanditModelDto dto = mapper.readValue(json, BanditModelDto.class);
            LinUcbModel model = dto.toModel();
            double[][] aInv = model.inverseA();          // 奇异 → 抛异常 → 视为未就绪
            double[] thetaHat = model.theta(aInv);
            log.debug("加载 bandit 模型:dim={}, n={}, order={}", model.dim(), model.getN(), dto.order());
            return new Loaded(dto.order(), aInv, thetaHat);
        } catch (Exception e) {
            log.debug("加载 bandit 模型失败: {}", e.getMessage());
            return null;
        }
    }

    /** 预计算后的在线模型:特征名顺序 + A⁻¹ + θ̂。 */
    private record Loaded(List<String> order, double[][] aInv, double[] thetaHat) {
    }

    /** 一次请求的打分句柄(封装 mode/alpha;Thompson 的 θ̃ 已在 forRequest 采好)。 */
    public static final class Session {
        private final List<String> order;
        private final double[] theta;      // linucb=θ̂;thompson=θ̃
        private final double[][] aInv;     // 仅 linucb 需要(出置信宽度);thompson=null
        private final double alpha;
        private final boolean linucb;

        Session(List<String> order, double[] theta, double[][] aInv, double alpha, boolean linucb) {
            this.order = order;
            this.theta = theta;
            this.aInv = aInv;
            this.alpha = alpha;
            this.linucb = linucb;
        }

        /**
         * 探索加成:linucb=θ̂ᵀx + alpha·√(xᵀA⁻¹x);thompson=θ̃ᵀx。空会话(模型未就绪)→ 0。
         *
         * @param featureSnapshot 排序返回的稠密特征快照(RankedItem.featureSnapshot());按 order 取 x,缺名→0
         */
        public double score(Map<String, Double> featureSnapshot) {
            if (order == null || featureSnapshot == null) {
                return 0.0;
            }
            double[] x = new double[order.size()];
            for (int i = 0; i < order.size(); i++) {
                x[i] = featureSnapshot.getOrDefault(order.get(i), 0.0);
            }
            double mean = LinUcbModel.dot(theta, x);
            if (linucb) {
                return mean + alpha * Math.sqrt(LinUcbModel.quadForm(x, aInv));
            }
            return mean;
        }
    }
}
