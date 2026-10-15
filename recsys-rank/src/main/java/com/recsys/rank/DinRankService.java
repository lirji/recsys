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
import com.recsys.rank.persistence.mapper.RatingSequenceMapper;
import com.recsys.rank.persistence.model.RatingSequenceRow;
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
 * DIN 行为序列建模排序(+ MMoE 多目标头)。
 *
 * <p>加载 {@code train_din.py} 导出的 {@code model_din.onnx}:四输入(稠密 {@code dense}[N,5]
 * + 稀疏 {@code sparse}[N,3] + 行为序列 {@code seq}[N,L] + 有效长度 {@code seq_len}[N]),
 * 双输出 {@code ctr}+{@code cvr}。候选 item 对用户历史序列做 target-attention,再走 MMoE 多目标头。
 *
 * <p><b>在线序列来源(R2:Redis 优先 + cache-aside + DB 兜底)</b>:优先读 Redis ZSet
 * {@code rt:user:seq:{id}}(近线/流作业按 ts 增量 ZADD 维护);未命中则回退查该用户近 {@code seqLen} 条
 * <b>≥4 的 RATING</b>(与 train_din.py 序列定义一致:gen-samples-mt 用 value≥4 正反馈构造序列)并
 * <b>cache-aside 回填</b> Redis(带 TTL),把"每请求直连 Postgres"降为"每用户每 TTL 至多一次 DB"。
 * 无论来源,序列都按 ts 升序(oldest→newest)喂 {@link SequenceEncoder},<b>每用户取一次</b>、广播到全部候选。
 * 开关 {@code recsys.rank.din-seq-redis-enabled}(默认开);Redis 不可用则透明退回直连 DB。
 *
 * <p>多目标融合同 {@link MmoeRankService}:finalScore = pCTR·(cvrBias + cvrWeight·pCVR)。
 * 仅当 {@code recsys.rank.strategy=din} 时创建;加载失败回退规则排序。
 */
@Service
@ConditionalOnProperty(name = "recsys.rank.strategy", havingValue = "din")
@EnableConfigurationProperties(RankProperties.class)
public class DinRankService implements RankService {

    private static final Logger log = LoggerFactory.getLogger(DinRankService.class);
    private final FeatureService featureService;
    private final ContentService contentService;
    private final RatingSequenceMapper mapper;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final RankProperties props;

    private OrtEnvironment env;
    private OrtSession session;
    private SparseFeatureEncoder sparseEncoder;
    private SequenceEncoder seqEncoder;
    private volatile boolean ready = false;

    public DinRankService(FeatureService featureService, ContentService contentService,
                          RatingSequenceMapper mapper, ObjectProvider<StringRedisTemplate> redisProvider,
                          RankProperties props) {
        this.featureService = featureService;
        this.contentService = contentService;
        this.mapper = mapper;
        this.redisProvider = redisProvider;
        this.props = props;
    }

    @PostConstruct
    void load() {
        try {
            sparseEncoder = SparseFeatureEncoder.load(props.getDinSchemaPath(), props.getDinCategoryVocabPath());
            seqEncoder = SequenceEncoder.load(props.getDinSchemaPath());
            byte[] model = readModel(props.getDinModelPath());
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(model, new OrtSession.SessionOptions());
            ready = true;
            log.info("DIN 序列模型加载成功:{};输入={};输出={};seqLen={}",
                    props.getDinModelPath(), session.getInputNames(), session.getOutputNames(), seqEncoder.seqLen());
        } catch (Throwable t) {
            ready = false;
            log.warn("DIN 模型加载失败,将回退规则排序:{}", t.toString());
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
            // 用户历史序列(每用户查一次,广播到全部候选)
            SequenceEncoder.Encoded enc = seqEncoder.encode(recentPositiveItems(userId, seqEncoder.seqLen()));

            Map<Long, Item> items = contentService.findByIds(candidateItemIds);
            Map<String, Double> userFeat = featureService.userFeatures(userId);
            // 批量预取候选特征(一次 pipeline),消除候选循环里逐个 HGETALL 的 N+1
            Map<Long, Map<String, Double>> itemFeats = featureService.itemFeatures(candidateItemIds);

            int n = candidateItemIds.size();
            List<String> denseOrder = sparseEncoder.denseOrder();   // 模型训练时的 dense_order(缺省基础 5 维)
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
                seq[i] = enc.seq();      // 同一用户序列,广播
                seqLen[i] = enc.len();
            }

            float[][] heads = predict(dense, sparse, seq, seqLen);  // [0]=ctr,[1]=cvr
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
            log.warn("DIN 打分异常,本次回退空结果(由上层兜底):{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 用户近 limit 条 ≥4 正反馈物品,返回 oldest→newest(与训练侧序列顺序一致)。
     * R2:Redis 优先 → 未命中回退 DB 并 cache-aside 回填。任何一步异常都不外泄(退空序列,冷用户不崩)。
     */
    private List<Long> recentPositiveItems(long userId, int limit) {
        StringRedisTemplate redis = props.isDinSeqRedisEnabled() ? redisProvider.getIfAvailable() : null;
        if (redis != null) {
            List<Long> cached = readRedisSeq(redis, userId, limit);
            if (!cached.isEmpty()) {
                return cached;
            }
        }
        // 回退 DB(带 ts),用于喂模型;顺带 cache-aside 回填 Redis
        List<long[]> rows = readDbSeq(userId, limit);   // 每项 [itemId, tsEpoch],oldest→newest
        if (redis != null && !rows.isEmpty()) {
            warmRedisSeq(redis, userId, rows, limit);
        }
        List<Long> out = new ArrayList<>(rows.size());
        for (long[] r : rows) {
            out.add(r[0]);
        }
        return out;
    }

    /** 读 Redis ZSet 最近 limit 个(score=ts),反转成 oldest→newest。异常/空 → 空 List。 */
    private List<Long> readRedisSeq(StringRedisTemplate redis, long userId, int limit) {
        try {
            Set<String> recentFirst = redis.opsForZSet()
                    .reverseRange(RedisKeys.userSeq(userId), 0, limit - 1);  // 最近在前
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
            Collections.reverse(ids);  // → oldest→newest
            return ids;
        } catch (Exception e) {
            log.debug("读 Redis 序列失败 user={},回退 DB: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /** 查 DB 近 limit 条 ≥4 正反馈,返回 [itemId, tsEpoch] 列表,oldest→newest。 */
    private List<long[]> readDbSeq(long userId, int limit) {
        try {
            List<RatingSequenceRow> rows = mapper.latestRatedItems(userId, limit);
            List<long[]> recentFirst = new ArrayList<>(rows.size());
            for (RatingSequenceRow row : rows) {
                recentFirst.add(row.asTimestampPair());
            }
            Collections.reverse(recentFirst);  // DESC → oldest→newest
            return recentFirst;
        } catch (Exception e) {
            log.debug("查询用户序列失败 user={}, 视作空序列: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /** cache-aside 回填:整表替换写 ZSet(member=itemId、score=ts)+ TTL。失败静默(不影响本次打分)。 */
    private void warmRedisSeq(StringRedisTemplate redis, long userId, List<long[]> rows, int limit) {
        try {
            String key = RedisKeys.userSeq(userId);
            Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
            for (long[] r : rows) {
                tuples.add(ZSetOperations.TypedTuple.of(String.valueOf(r[0]), (double) r[1]));
            }
            redis.delete(key);
            redis.opsForZSet().add(key, tuples);
            // 定长护栏:即便被增量 ZADD 撑大,也只保留最近 limit 个(移除低分/旧项)
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
            throw new IllegalStateException("DIN 输出缺张量: " + name);
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
        throw new IllegalStateException("DIN 输出 " + name + " 非浮点张量");
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static byte[] readModel(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length());
            try (InputStream in = DinRankService.class.getClassLoader().getResourceAsStream(cp)) {
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
