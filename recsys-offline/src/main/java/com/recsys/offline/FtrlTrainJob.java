package com.recsys.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.feature.FtrlHashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 每正例按 {@code --neg} 采样未交互 item 为负例(热度无关的均匀采样,教学从简)。
 *
 * <p>参数:--incremental、--neg(默认 2)、--epochs(默认 1)、--min-rating(4.0)、--max-ts、--seed(42)、
 * --alpha(0.1)、--beta(1.0)、--l1(1.0)、--l2(1.0)。特征哈希契约见 {@link FtrlHashing}(与在线一致)。
 */
@Component
public class FtrlTrainJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(FtrlTrainJob.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public FtrlTrainJob(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public String name() {
        return "train-ftrl";
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean incremental = args.containsOption("incremental");
        int neg = intArg(args, "neg", 2);
        int epochs = intArg(args, "epochs", 1);
        double minRating = doubleArg(args, "min-rating", 4.0);
        Long maxTs = BehaviorQuery.maxTs(args);
        String bt = BehaviorQuery.table(args);   // #2
        long seed = intArg(args, "seed", 42);
        FtrlProximal model = new FtrlProximal(
                doubleArg(args, "alpha", 0.1), doubleArg(args, "beta", 1.0),
                doubleArg(args, "l1", 1.0), doubleArg(args, "l2", 1.0));

        if (incremental) {
            loadState(model);
        }

        // 负采样物品池
        List<Long> items = jdbc.queryForList("SELECT item_id FROM item", Long.class);
        if (items.isEmpty()) {
            log.warn("item 表为空,先跑 import-items");
            return;
        }
        // 正样本 (user,item)
        List<long[]> pos = new ArrayList<>();
        jdbc.query(BehaviorQuery.positiveFeedbackSql(bt, "user_id, item_id", maxTs),
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        pos.add(new long[]{rs.getLong("user_id"), rs.getLong("item_id")}),
                BehaviorQuery.params(minRating, maxTs));
        if (pos.size() < 100) {
            log.warn("正样本太少({} 条),先跑 import-behavior", pos.size());
            return;
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
                steps += 1 + neg;
            }
        }

        Map<Integer, Double> weights = model.serveWeights();
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
        bias.add(model.biasState()[0]);
        bias.add(model.biasState()[1]);
        ObjectNode c = root.putObject("c");
        for (var en : model.stateCoords().entrySet()) {
            ArrayNode zn = c.putArray(String.valueOf(en.getKey()));
            zn.add(en.getValue()[0]);
            zn.add(en.getValue()[1]);
        }
        writeJson(RedisKeys.FTRL_STATE, root);
    }

    private void loadState(FtrlProximal model) {
        try {
            String json = redis.opsForValue().get(RedisKeys.FTRL_STATE);
            if (json == null || json.isBlank()) {
                log.info("无 ftrl:state,--incremental 退化为从零训");
                return;
            }
            JsonNode root = mapper.readTree(json);
            double[] bias = {root.path("bias").path(0).asDouble(), root.path("bias").path(1).asDouble()};
            Map<Integer, double[]> coords = new HashMap<>();
            JsonNode c = root.path("c");
            c.fieldNames().forEachRemaining(k -> {
                JsonNode v = c.get(k);
                coords.put(Integer.parseInt(k), new double[]{v.path(0).asDouble(), v.path(1).asDouble()});
            });
            model.loadState(bias, coords);
            log.info("已 warm-start:载入 {} 个坐标状态", coords.size());
        } catch (Exception ex) {
            log.warn("载入 ftrl:state 失败,从零训: {}", ex.getMessage());
        }
    }

    private void writeJson(String key, ObjectNode node) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(node));
        } catch (Exception e) {
            log.warn("写 {} 失败: {}", key, e.getMessage());
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static double doubleArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }
}
