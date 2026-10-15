package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import com.pgvector.PGvector;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.recsys.offline.persistence.mapper.derived.LookalikeMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 作业 lookalike(A3):Look-alike 人群扩散 —— 从种子用户的行为向量出发,ANN 找相似用户,
 * 把"种子 + 相似用户"物化成人群包 Redis set {@code ad:audience:{id}},供在线广告定向(SISMEMBER)。
 *
 * <p>算法:对每个 audience,取其种子用户的 {@code user_embedding} 求<b>质心</b>,在 user_embedding 上做
 * pgvector 余弦 ANN 取 top-N 相似用户 → 与种子并集写入人群包(保留原先删除后写入的发布顺序)。这是广告"智能定向"里
 * 用向量相似把小种子扩成大受众的经典做法(种子人群向量扩散)。
 *
 * <p>参数:--top-n(每个人群扩散上限,默认 1000)、--audience(只处理某个 audience_id,默认全部)。
 * 种子无可用向量的人群保留旧包并继续其他人群，最终报告未完整更新；缺表或查询失败仍向上抛出。
 */
@Component
public class LookalikeJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(LookalikeJob.class);

    private final LookalikeMapper mapper;
    private final StringRedisTemplate redis;

    /** 派生Mapper绑定由宿主配置确定，作业仅保留质心算法与原Redis发布流程。 */
    public LookalikeJob(LookalikeMapper mapper, StringRedisTemplate redis) {
        this.mapper = mapper;
        this.redis = redis;
    }

    /** 保持人群扩散命令入口。 */
    @Override
    public String name() {
        return "lookalike";
    }

    /** 当前人群查询完成后才删除旧包，失败边界沿用原实现。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        int topN = intArg(args, "top-n", 1000);
        Long only = args.containsOption("audience") ? JobArguments.longInteger(args, "audience", 0) : null;
        if (topN < 0) throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);

        List<Long> audiences = only != null ? List.of(only) : mapper.audienceIds();
        if (audiences.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_NO_AUDIENCE_SEEDS);
        }

        int missingVectors = 0;
        for (Long aud : audiences) {
            List<Long> seeds = mapper.seedUsers(aud);
            List<float[]> seedVecs = seedVectors(seeds);
            if (seedVecs.isEmpty()) {
                missingVectors++;
                log.warn("{}；人群={}", ErrorCode.JOB_AUDIENCE_VECTORS_UNAVAILABLE.displayMessage(), aud);
                continue;
            }
            float[] centroid = centroid(seedVecs);
            // ANN 扩散:质心的 top-N 相似用户
            PGvector pv = new PGvector(centroid);
            List<Long> expanded = mapper.nearestUsers(pv, topN);

            Set<String> pkg = new LinkedHashSet<>();
            for (Long s : seeds) {
                pkg.add(String.valueOf(s));   // 种子必在包内
            }
            for (Long u : expanded) {
                pkg.add(String.valueOf(u));
            }
            String key = RedisKeys.adAudience(aud);
            redis.delete(key);
            redis.opsForSet().add(key, pkg.toArray(new String[0]));
            log.info("lookalike 人群 {} 完成:种子 {} → 扩散 {}(top-n={})", aud, seeds.size(), pkg.size(), topN);
        }
        // 成功人群仍沿原规则发布，但缺向量不能让DAG误判整批已完成。
        if (missingVectors > 0) throw new PublicErrorException(ErrorCode.JOB_AUDIENCE_VECTORS_UNAVAILABLE);
    }

    /** 取种子用户的 user_embedding(文本解析,避免 PGvector 类型注册问题,与召回侧降级一致)。 */
    private List<float[]> seedVectors(List<Long> seeds) {
        List<float[]> out = new ArrayList<>();
        if (seeds.isEmpty()) {
            return out;
        }
        mapper.scanSeedVectors(seeds, context -> {
            String text = context.getResultObject();
            if (text != null) out.add(parseVector(text));
        });
        return out;
    }

    /** 向量质心(逐维均值)。纯函数,供单测。要求非空且各向量等维。 */
    static float[] centroid(List<float[]> vecs) {
        int dim = vecs.get(0).length;
        double[] sum = new double[dim];
        for (float[] v : vecs) {
            for (int i = 0; i < dim; i++) {
                sum[i] += v[i];
            }
        }
        float[] c = new float[dim];
        for (int i = 0; i < dim; i++) {
            c[i] = (float) (sum[i] / vecs.size());
        }
        return c;
    }

    private static float[] parseVector(String s) {
        String body = s.replace("[", "").replace("]", "");
        String[] parts = body.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i].trim());
        }
        return v;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return JobArguments.integer(a, k, def);
    }
}
