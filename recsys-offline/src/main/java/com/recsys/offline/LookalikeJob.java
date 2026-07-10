package com.recsys.offline;

import com.pgvector.PGvector;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * pgvector 余弦 ANN 取 top-N 相似用户 → 与种子并集写入人群包(原子替换)。这是广告"智能定向"里
 * 用向量相似把小种子扩成大受众的经典做法(种子人群向量扩散)。
 *
 * <p>参数:--top-n(每个人群扩散上限,默认 1000)、--audience(只处理某个 audience_id,默认全部)。
 * 缺 user_embedding / 种子无向量的人群跳过。
 */
@Component
public class LookalikeJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(LookalikeJob.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public LookalikeJob(@org.springframework.beans.factory.annotation.Qualifier("derivedJdbc") JdbcTemplate jdbc,
                        StringRedisTemplate redis) {   // #3:user_embedding 走派生库
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public String name() {
        return "lookalike";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int topN = intArg(args, "top-n", 1000);
        Long only = args.containsOption("audience") ? Long.parseLong(args.getOptionValues("audience").get(0)) : null;

        List<Long> audiences = only != null ? List.of(only) : jdbc.queryForList(
                "SELECT DISTINCT audience_id FROM ad_audience_seed WHERE audience_id IS NOT NULL", Long.class);
        if (audiences.isEmpty()) {
            log.warn("无 ad_audience_seed 种子人群;先灌种子(audience_id,user_id)再跑 lookalike");
            return;
        }

        for (Long aud : audiences) {
            List<Long> seeds = jdbc.queryForList(
                    "SELECT user_id FROM ad_audience_seed WHERE audience_id=?", Long.class, aud);
            List<float[]> seedVecs = seedVectors(seeds);
            if (seedVecs.isEmpty()) {
                log.warn("人群 {} 的种子无 user_embedding,跳过", aud);
                continue;
            }
            float[] centroid = centroid(seedVecs);
            // ANN 扩散:质心的 top-N 相似用户
            PGvector pv = new PGvector(centroid);
            List<Long> expanded = jdbc.query(
                    "SELECT user_id FROM user_embedding ORDER BY embedding <=> ? LIMIT ?",
                    ps -> {
                        ps.setObject(1, pv);
                        ps.setInt(2, topN);
                    },
                    (rs, n) -> rs.getLong("user_id"));

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
    }

    /** 取种子用户的 user_embedding(文本解析,避免 PGvector 类型注册问题,与召回侧降级一致)。 */
    private List<float[]> seedVectors(List<Long> seeds) {
        List<float[]> out = new ArrayList<>();
        if (seeds.isEmpty()) {
            return out;
        }
        String ph = String.join(",", java.util.Collections.nCopies(seeds.size(), "?"));
        jdbc.query("SELECT embedding::text FROM user_embedding WHERE user_id IN (" + ph + ")",
                rs -> {
                    String s = rs.getString(1);
                    if (s != null) {
                        out.add(parseVector(s));
                    }
                }, seeds.toArray());
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
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0).trim()) : def;
    }
}
