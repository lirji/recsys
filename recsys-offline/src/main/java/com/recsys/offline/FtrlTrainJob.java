package com.recsys.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.feature.FtrlHashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.recsys.offline.persistence.mapper.primary.PositiveFeedbackMapper;
import com.recsys.offline.persistence.mapper.primary.TrainingCatalogMapper;
import com.recsys.offline.persistence.model.BehaviorSource;
import com.recsys.content.persistence.model.CatalogTable;
import com.recsys.offline.persistence.model.PositiveFeedbackFilter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 作业 train-ftrl:近线增量训练 FTRL-Proximal 逻辑回归(协同过滤味的轻量 CTR 模型),
 * 学 {@code P(正反馈 | user,item)},写 Redis {@code ftrl:weights}(服务)+ {@code ftrl:state}(增量续训)。
 * 在线 {@code FtrlScorer} 查权重给候选打分,作为融合的近线学习信号(补 T+1 批模型的时效短板)。
 *
 * <p><b>近线增量</b>:{@code --incremental} 从 {@code ftrl:state} warm-start,只对新窗口(可配 {@code --max-ts}
 * 或调度器按小时切片)增量更新 → 小时级刷新;不传则从零训。样本:正反馈对 (user,item) 为正例,
 * 每正例按 {@code --neg} 从全物品池均匀采样负例；原算法不排除已交互物品(教学从简)。
 *
 * <p>参数:--incremental、--neg(默认 2)、--epochs(默认 1)、--min-rating(4.0)、--max-ts、--seed(42)、
 * --alpha(0.1)、--beta(1.0)、--l1(1.0)、--l2(1.0)。特征哈希契约见 {@link FtrlHashing}(与在线一致)。
 */
@Component
public class FtrlTrainJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(FtrlTrainJob.class);
    private static final int MIN_POSITIVE_SAMPLES = 100;
    private static final int COORD_Z = 0;
    private static final int COORD_N = 1;
    private static final int COORDINATE_SIZE = 2;

    private final TrainingCatalogMapper catalog;
    private final PositiveFeedbackMapper feedback;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 两类训练输入使用主源Mapper，负采样池保留原扫描顺序以维持随机训练结果。 */
    public FtrlTrainJob(PositiveFeedbackMapper feedback, TrainingCatalogMapper catalog, StringRedisTemplate redis) {
        this.catalog = catalog;
        this.feedback = feedback;
        this.redis = redis;
    }

    /** 保留原近线训练命令名。 */
    @Override
    public String name() {
        return "train-ftrl";
    }

    /** 两键按原顺序发布，任何失败必须保留未知或部分结果语义。 */
    @Override
    public void run(ApplicationArguments args) {
        try {
            train(args);
        } catch (PublicErrorException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new PublicErrorException(ErrorCode.JOB_FTRL_FAILED, failure);
        }
    }

    private void train(ApplicationArguments args) {
        boolean incremental = args.containsOption("incremental");
        int neg = JobArguments.integer(args, "neg", 2);
        int epochs = JobArguments.integer(args, "epochs", 1);
        double minRating = JobArguments.decimal(args, "min-rating", 4.0);
        Long maxTs = BehaviorQuery.maxTs(args);
        BehaviorSource source = BehaviorQuery.source(args);
        CatalogTable itemTable = ItemQuery.source(args);
        long seed = JobArguments.integer(args, "seed", 42);
        double alpha = JobArguments.decimal(args, "alpha", 0.1), beta = JobArguments.decimal(args, "beta", 1.0);
        double l1 = JobArguments.decimal(args, "l1", 1.0), l2 = JobArguments.decimal(args, "l2", 1.0);
        if (alpha <= 0 || beta < 0 || l1 < 0 || l2 < 0 || neg < 0 || epochs < 0) {
            throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        }
        FtrlProximal model = new FtrlProximal(alpha, beta, l1, l2);

        if (incremental) {
            loadState(model);
        }

        // 负采样物品池
        List<Long> items = catalog.itemIds(itemTable);
        if (items.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_FTRL_NO_INPUT);
        }
        // 正样本 (user,item)
        List<long[]> pos = new ArrayList<>();
        feedback.scanPairs(new PositiveFeedbackFilter(source, minRating, maxTs), context -> {
            var row = context.getResultObject();
            pos.add(new long[]{row.userId(), row.itemId()});
        });
        if (pos.size() < MIN_POSITIVE_SAMPLES) {
            throw new PublicErrorException(ErrorCode.JOB_FTRL_NO_INPUT);
        }
        log.info("train-ftrl 开始:正样本 {} 对, item 池 {}, neg={}, epochs={}, incremental={}",
                pos.size(), items.size(), neg, epochs, incremental);

        Random rng = new Random(seed);
        long steps = 0;
        for (int e = 0; e < epochs; e++) {
            java.util.Collections.shuffle(pos, rng);
            for (long[] p : pos) {
                long u = p[0], it = p[1];
                model.update(FtrlHashing.features(u, it), 1.0);
                for (int k = 0; k < neg; k++) {
                    long negItem = items.get(rng.nextInt(items.size()));
                    model.update(FtrlHashing.features(u, negItem), 0.0);
                }
                steps += 1L + neg;
            }
        }

        Map<Integer, Double> weights = model.serveWeights();
        // 两键都依赖完整有效的训练结果，不能先写权重才发现续训状态已损坏。
        if (!validCoordinate(model.biasState()) || !Double.isFinite(model.serveBias())
                || model.stateCoords().values().stream().anyMatch(state -> !validCoordinate(state))
                || weights.values().stream().anyMatch(value -> !Double.isFinite(value))) {
            throw new PublicErrorException(ErrorCode.JOB_FTRL_MODEL_INVALID);
        }
        saveWeights(model.serveBias(), weights);
        saveState(model);
        log.info("train-ftrl 完成:更新 {} 步;非零权重 {} 个(bias={});已写 {} + {}",
                steps, weights.size(), round4(model.serveBias()),
                RedisKeys.FTRL_WEIGHTS, RedisKeys.FTRL_STATE);
    }

    // ---------- Redis 序列化 ----------

    private void saveWeights(double bias, Map<Integer, Double> weights) {
        ObjectNode root = mapper.createObjectNode();
        root.put("bias", bias);
        ObjectNode w = root.putObject("w");
        weights.forEach((idx, val) -> w.put(String.valueOf(idx), val));
        writeJson(RedisKeys.FTRL_WEIGHTS, root);
    }

    private void saveState(FtrlProximal model) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode bias = root.putArray("bias");
        bias.add(model.biasState()[COORD_Z]);
        bias.add(model.biasState()[COORD_N]);
        ObjectNode c = root.putObject("c");
        for (var en : model.stateCoords().entrySet()) {
            ArrayNode zn = c.putArray(String.valueOf(en.getKey()));
            zn.add(en.getValue()[COORD_Z]);
            zn.add(en.getValue()[COORD_N]);
        }
        writeJson(RedisKeys.FTRL_STATE, root);
    }

    /** 先完整解析后装载，坏JSON仍安全降级；Redis读取异常由作业边界传播。 */
    private void loadState(FtrlProximal model) {
        String json = redis.opsForValue().get(RedisKeys.FTRL_STATE);
        if (json != null && !json.isBlank()) {
            try {
                JsonNode root = mapper.readTree(json);
                if (root == null || !root.isObject() || !root.path("c").isObject()) {
                    throw new PublicErrorException(ErrorCode.JOB_FTRL_MODEL_INVALID);
                }
                double[] bias = readCoordinate(root.path("bias"));
                Map<Integer, double[]> coords = new HashMap<>();
                var fields = root.path("c").fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    int key = Integer.parseInt(entry.getKey());
                    if (!Integer.toString(key).equals(entry.getKey())) {
                        throw new PublicErrorException(ErrorCode.JOB_FTRL_MODEL_INVALID);
                    }
                    coords.put(key, readCoordinate(entry.getValue()));
                }
                model.loadState(bias, coords);
                log.info("已 warm-start:载入 {} 个坐标状态", coords.size());
                return;
            } catch (JsonProcessingException | RuntimeException ignored) {
                // 保留既有坏状态从零训练策略；固定目录提示，禁止输出任意JSON或解析异常。
            }
        }
        ErrorCode code = ErrorCode.JOB_FTRL_MODEL_FALLBACK;
        log.warn("[{}] {}", code.code(), code.message());
    }

    private static double[] readCoordinate(JsonNode node) {
        if (!node.isArray() || node.size() != COORDINATE_SIZE || !node.path(COORD_Z).isNumber()
                || !node.path(COORD_N).isNumber()) {
            throw new PublicErrorException(ErrorCode.JOB_FTRL_MODEL_INVALID);
        }
        double[] coordinate = {node.get(COORD_Z).doubleValue(), node.get(COORD_N).doubleValue()};
        if (!validCoordinate(coordinate)) throw new PublicErrorException(ErrorCode.JOB_FTRL_MODEL_INVALID);
        return coordinate;
    }

    private static boolean validCoordinate(double[] coordinate) {
        return coordinate.length == COORDINATE_SIZE && Double.isFinite(coordinate[COORD_Z])
                && Double.isFinite(coordinate[COORD_N]) && coordinate[COORD_N] >= 0;
    }

    private void writeJson(String key, ObjectNode node) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(node));
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new PublicErrorException(ErrorCode.JOB_FTRL_FAILED, failure);
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

}
