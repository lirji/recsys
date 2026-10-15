package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.mapper.derived.ItemEmbeddingMapper;
import com.recsys.offline.persistence.mapper.derived.UserEmbeddingMapper;
import com.recsys.offline.persistence.mapper.primary.PositiveFeedbackMapper;
import com.recsys.offline.persistence.model.BehaviorSource;
import com.recsys.offline.persistence.model.PositiveFeedbackFilter;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 作业 user-embedding:把每个用户正反馈物品的 item_embedding 按评分加权平均,
 * L2 归一化后写入 user_embedding,使 VectorRecaller 对真实用户(而非手造测试向量)生效。
 *
 * <p>user_vec(u) = normalize( Σ_{i∈正反馈} weight(u,i)·decay(age_i) · item_vec(i) )
 * <br>weight:RATING 用 value,CLICK/LIKE/PLAY 用固定权重;decay:半衰期时间衰减
 * {@code 0.5^(age/halfLife)},让用户向量偏向近期兴趣(修 VECTOR 召回"纯长期、无新鲜度")。
 * 只有已灌 item_embedding 的物品参与(当前 1000 条),覆盖不到向量的用户不产出。
 *
 * <p>user_embedding 完全派生自行为,默认整表重建(--no-rebuild 可改为增量 upsert)。
 * 参数:--min-rating(默认 4.0)、--half-life-days(默认 180;&lt;=0 关闭时间衰减)。
 */
@Component
public class UserEmbeddingJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(UserEmbeddingJob.class);

    private final PositiveFeedbackMapper feedback;
    private final ItemEmbeddingMapper items;
    private final UserEmbeddingMapper users;

    /** 主源行为与派生向量分别绑定Mapper，权威数据来源和算法保持。 */
    public UserEmbeddingJob(PositiveFeedbackMapper feedback, ItemEmbeddingMapper items, UserEmbeddingMapper users) {
        this.feedback = feedback;
        this.items = items;
        this.users = users;
    }

    /** 保持原用户向量构建命令。 */
    @Override
    public String name() {
        return "user-embedding";
    }

    /** 仅有产出时才重建；单条写入和外层事务保持原失败语义。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        double minRating = JobArguments.decimal(args, "min-rating", 4.0);
        boolean rebuild = !args.containsOption("no-rebuild");
        double halfLifeDays = JobArguments.decimal(args, "half-life-days", 180.0);  // 时间衰减半衰期(天);<=0 关闭
        Long maxTs = BehaviorQuery.maxTs(args);   // 严格 eval:只用切分点前的行为聚合用户向量
        BehaviorSource bt = BehaviorQuery.source(args);    // #2:行为读来源表(默认 user_behavior)

        // 时间衰减参考时刻:正反馈集里最近一次行为(严格 eval 下 ≤ maxTs)。相对它算每条行为的"年龄",
        // 越久远权重越低,让 user_embedding 偏向近期兴趣,修 VECTOR 召回"纯长期、无新鲜度"。
        Double refEpoch = halfLifeDays > 0
                ? feedback.maxEpoch(new PositiveFeedbackFilter(bt, minRating, maxTs))
                : null;
        final boolean decayOn = halfLifeDays > 0 && refEpoch != null;
        final double refTs = refEpoch != null ? refEpoch : 0.0;

        // 1. 全量加载物品向量到内存(当前约 1000 条 × 768 维,约 3MB)
        Map<Long, float[]> itemVecs = loadItemVectors();
        if (itemVecs.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_NO_ITEM_VECTORS);
        }
        int dim = itemVecs.values().iterator().next().length;
        log.info("加载物品向量 {} 条(dim={});min-rating={}, rebuild={}, half-life-days={}({})",
                itemVecs.size(), dim, minRating, rebuild, halfLifeDays, decayOn ? "开" : "关");

        // 2. 流式累加用户正反馈向量
        Map<Long, float[]> acc = new HashMap<>();
        Map<Long, Double> wsum = new HashMap<>();
        int[] hit = {0};
        feedback.scanWeighted(new PositiveFeedbackFilter(bt, minRating, maxTs), context -> {
                    var row = context.getResultObject();
                    long u = row.userId();
                    long it = row.itemId();
                    float[] v = itemVecs.get(it);
                    if (v == null) {
                        return; // 该物品无向量,跳过
                    }
                    double w = weight(row.action(), row.value() == null ? 0.0 : row.value());
                    if (decayOn) {
                        // 半衰期时间衰减:age=halfLife → 权重减半;近期行为权重≈1,久远行为趋 0
                        double ageDays = (refTs - (row.epoch() == null ? 0.0 : row.epoch())) / 86400.0;
                        if (ageDays > 0) {
                            w *= Math.pow(0.5, ageDays / halfLifeDays);
                        }
                    }
                    float[] a = acc.computeIfAbsent(u, k -> new float[dim]);
                    for (int d = 0; d < dim; d++) {
                        a[d] += (float) (w * v[d]);
                    }
                    wsum.merge(u, w, Double::sum);
                    hit[0]++;
                });
        log.info("命中带向量的正反馈 {} 条,覆盖用户 {} 个", hit[0], acc.size());
        if (acc.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_NO_USER_VECTORS);
        }

        // 3. L2 归一化并写库
        if (rebuild) {
            users.truncateAll();
        }
        int written = 0;
        for (var e : acc.entrySet()) {
            float[] vec = e.getValue();
            l2normalize(vec); // 加权和方向即可,模长归一便于余弦
            upsert(e.getKey(), vec);
            if (++written % 200 == 0) {
                log.info("已写 {} / {} 用户向量...", written, acc.size());
            }
        }
        Long total = users.countAll();
        log.info("user-embedding 完成:写入 {} 个用户向量;user_embedding 总数 {}", written, total);
    }

    private Map<Long, float[]> loadItemVectors() {
        Map<Long, float[]> map = new HashMap<>();
        // 用 ::text 读取,避免 PGvector 类型注册问题(与 VectorRecaller 降级路径一致)
        items.scanVectors(context -> {
            var row = context.getResultObject();
            String s = row.vector();
            if (s == null) {
                return;
            }
            map.put(row.itemId(), parseVector(s));
        });
        return map;
    }

    private static float[] parseVector(String s) {
        s = s.replace("[", "").replace("]", "");
        String[] parts = s.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i].trim());
        }
        return v;
    }

    private static double weight(String action, double value) {
        return switch (action) {
            case "RATING" -> value > 0 ? value : 1.0;
            case "LIKE" -> 2.0;
            default -> 1.0; // CLICK / PLAY
        };
    }

    private static void l2normalize(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
    }

    private void upsert(long userId, float[] vec) {
        users.upsert(userId, new PGvector(vec));
    }

}
