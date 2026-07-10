package com.recsys.rank;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.feature.FeatureService;
import com.recsys.common.rank.RankService;
import com.recsys.common.rank.RankedItem;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DIEN 兴趣演化排序(R1)。加载 {@code train_dien.py} 导出的 {@code model_dien.onnx}。
 *
 * <p><b>与 {@link DinRankService} 的在线契约完全相同</b>——四输入(dense[N,5]+sparse[N,3]
 * +seq[N,L]+seq_len[N])、双输出(ctr+cvr),共用 {@link SparseFeatureEncoder}/{@link SequenceEncoder}
 * 与 R2 的行为序列来源(Redis {@code rt:user:seq} 优先 → DB 回退 + cache-aside)。DIEN 与 DIN 的差异
 * 只在<b>模型内部</b>(GRU 兴趣抽取 + AUGRU 兴趣演化 vs DIN 的静态注意力池化),线上 encoder/取数一概不变。
 * 因此本类是 DinRankService 的镜像,仅换模型文件与 {@code strategy=dien}。
 *
 * <p>多目标融合同 {@link MmoeRankService}:finalScore = pCTR·(cvrBias + cvrWeight·pCVR)。
 * 仅当 {@code recsys.rank.strategy=dien} 时创建;加载失败回退规则排序。
 */
@Service
@ConditionalOnProperty(name = "recsys.rank.strategy", havingValue = "dien")
@EnableConfigurationProperties(RankProperties.class)
public class DienRankService implements RankService {

    private static final Logger log = LoggerFactory.getLogger(DienRankService.class);
    private static final String SEQ_SQL =
            "SELECT item_id, extract(epoch from ts) AS ts FROM user_behavior "
                    + "WHERE user_id=? AND action='RATING' AND value>=4 ORDER BY ts DESC LIMIT ?";

    private final FeatureService featureService;
    private final ContentService contentService;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final RankProperties props;

    private OrtEnvironment env;
    private OrtSession session;
    private SparseFeatureEncoder sparseEncoder;
    private SequenceEncoder seqEncoder;
    private volatile boolean ready = false;

    public DienRankService(FeatureService featureService, ContentService contentService,
                           JdbcTemplate jdbc, ObjectProvider<StringRedisTemplate> redisProvider,
                           RankProperties props) {
        this.featureService = featureService;
        this.contentService = contentService;
        this.jdbc = jdbc;
        this.redisProvider = redisProvider;
        this.props = props;
    }

    @PostConstruct
    void load() {
        try {
            sparseEncoder = SparseFeatureEncoder.load(props.getDienSchemaPath(), props.getDienCategoryVocabPath());
            seqEncoder = SequenceEncoder.load(props.getDienSchemaPath());
            byte[] model = readModel(props.getDienModelPath());
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(model, new OrtSession.SessionOptions());
            ready = true;
            log.info("DIEN 兴趣演化模型加载成功:{};输入={};输出={};seqLen={}",
                    props.getDienModelPath(), session.getInputNames(), session.getOutputNames(), seqEncoder.seqLen());
        } catch (Throwable t) {
            ready = false;
            log.warn("DIEN 模型加载失败,将回退规则排序:{}", t.toString());
        }
    }

    public boolean isReady() {
        return ready;
    }

    @Override
    public List<RankedItem> rank(long userId, List<Long> candidateItemIds, String scene) {
        if (!ready || candidateItemIds == null || candidateItemIds.isEmpty()) {
            return List.of();
        }
        try {
            SequenceEncoder.Encoded enc = seqEncoder.encode(recentPositiveItems(userId, seqEncoder.seqLen()));

            Map<Long, Item> items = contentService.findByIds(candidateItemIds);
            Map<String, Double> userFeat = featureService.userFeatures(userId);
            Map<Long, Map<String, Double>> itemFeats = featureService.itemFeatures(candidateItemIds);

            int n = candidateItemIds.size();
            List<String> denseOrder = sparseEncoder.denseOrder();
            int dim = denseOrder.size();
            int L = seqEncoder.seqLen();
            float[][] dense = new float[n][dim];
            long[][] sparse = new long[n][3];
            long[][] seq = new long[n][L];
            long[] seqLen = new long[n];
            double[][] raw = new double[n][];
            for (int i = 0; i < n; i++) {
                long itemId = candidateItemIds.get(i);
                Item it = items.get(itemId);
                String cat = it == null ? null : it.category();
                double[] f = FeatureAssembler.assemble(userFeat, itemFeats.getOrDefault(itemId, Map.of()), cat, denseOrder);
                raw[i] = f;
                for (int d = 0; d < dim; d++) {
                    dense[i][d] = (float) f[d];
                }
                sparse[i] = sparseEncoder.encode(userId, itemId, cat);
                seq[i] = enc.seq();
                seqLen[i] = enc.len();
            }

            float[][] heads = predict(dense, sparse, seq, seqLen);
            double cvrBias = props.getMultiTask().getCvrBias();
            double cvrWeight = props.getMultiTask().getCvrWeight();
            List<RankedItem> ranked = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double pctr = heads[0][i];
                double pcvr = heads[1][i];
                double score = pctr * (cvrBias + cvrWeight * pcvr);
                Map<String, Double> snap = new LinkedHashMap<>(FeatureAssembler.snapshot(raw[i], denseOrder));
                snap.put("pCTR", round(pctr));
                snap.put("pCVR", round(pcvr));
                ranked.add(new RankedItem(candidateItemIds.get(i), score, snap));
            }
            ranked.sort(Comparator.comparingDouble(RankedItem::score).reversed());
            return ranked;
        } catch (Exception e) {
            log.warn("DIEN 打分异常,本次回退空结果(由上层兜底):{}", e.getMessage());
            return List.of();
        }
    }

    // ---- R2:行为序列来源(Redis 优先 + cache-aside + DB 兜底),与 DinRankService 一致 ----

    private List<Long> recentPositiveItems(long userId, int limit) {
        StringRedisTemplate redis = props.isDinSeqRedisEnabled() ? redisProvider.getIfAvailable() : null;
        if (redis != null) {
            List<Long> cached = readRedisSeq(redis, userId, limit);
            if (!cached.isEmpty()) {
                return cached;
            }
        }
        List<long[]> rows = readDbSeq(userId, limit);
        if (redis != null && !rows.isEmpty()) {
            warmRedisSeq(redis, userId, rows, limit);
        }
        List<Long> out = new ArrayList<>(rows.size());
        for (long[] r : rows) {
            out.add(r[0]);
        }
        return out;
    }

    private List<Long> readRedisSeq(StringRedisTemplate redis, long userId, int limit) {
        try {
            Set<String> recentFirst = redis.opsForZSet()
                    .reverseRange(RedisKeys.userSeq(userId), 0, limit - 1);
            if (recentFirst == null || recentFirst.isEmpty()) {
                return List.of();
            }
            List<Long> ids = new ArrayList<>(recentFirst.size());
            for (String s : recentFirst) {
                try {
                    ids.add(Long.parseLong(s));
                } catch (NumberFormatException ignore) {
                    // 脏成员跳过
                }
            }
            Collections.reverse(ids);
            return ids;
        } catch (Exception e) {
            log.debug("读 Redis 序列失败 user={},回退 DB: {}", userId, e.getMessage());
            return List.of();
        }
    }

    private List<long[]> readDbSeq(long userId, int limit) {
        try {
            List<long[]> recentFirst = jdbc.query(SEQ_SQL,
                    (rs, n) -> new long[]{rs.getLong("item_id"), (long) rs.getDouble("ts")},
                    userId, limit);
            Collections.reverse(recentFirst);
            return recentFirst;
        } catch (Exception e) {
            log.debug("查询用户序列失败 user={}, 视作空序列: {}", userId, e.getMessage());
            return List.of();
        }
    }

    private void warmRedisSeq(StringRedisTemplate redis, long userId, List<long[]> rows, int limit) {
        try {
            String key = RedisKeys.userSeq(userId);
            Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
            for (long[] r : rows) {
                tuples.add(ZSetOperations.TypedTuple.of(String.valueOf(r[0]), (double) r[1]));
            }
            redis.delete(key);
            redis.opsForZSet().add(key, tuples);
            redis.opsForZSet().removeRange(key, 0, -(limit + 1L));
            redis.expire(key, Duration.ofSeconds(props.getDinSeqTtlSeconds()));
        } catch (Exception e) {
            log.debug("回填 Redis 序列失败 user={}(不影响本次打分): {}", userId, e.getMessage());
        }
    }

    private float[][] predict(float[][] dense, long[][] sparse, long[][] seq, long[] seqLen) throws Exception {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor d = OnnxTensor.createTensor(env, dense);
             OnnxTensor s = OnnxTensor.createTensor(env, sparse);
             OnnxTensor q = OnnxTensor.createTensor(env, seq);
             OnnxTensor l = OnnxTensor.createTensor(env, seqLen)) {
            inputs.put("dense", d);
            inputs.put("sparse", s);
            inputs.put("seq", q);
            inputs.put("seq_len", l);
            try (OrtSession.Result result = session.run(inputs)) {
                return new float[][]{column(result, "ctr"), column(result, "cvr")};
            }
        }
    }

    private static float[] column(OrtSession.Result result, String name) throws Exception {
        Optional<OnnxValue> v = result.get(name);
        if (v.isEmpty()) {
            throw new IllegalStateException("DIEN 输出缺张量: " + name);
        }
        Object val = v.get().getValue();
        if (val instanceof float[][] p) {
            float[] out = new float[p.length];
            for (int i = 0; i < p.length; i++) {
                out[i] = p[i][p[i].length - 1];
            }
            return out;
        }
        if (val instanceof float[] p1) {
            return p1;
        }
        throw new IllegalStateException("DIEN 输出 " + name + " 非浮点张量");
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static byte[] readModel(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length());
            try (InputStream in = DienRankService.class.getClassLoader().getResourceAsStream(cp)) {
                if (in == null) {
                    throw new IllegalStateException("classpath 未找到模型: " + cp);
                }
                return in.readAllBytes();
            }
        }
        return Files.readAllBytes(Path.of(path));
    }

    @PreDestroy
    void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignore) {
            // 关闭失败无所谓
        }
    }
}
