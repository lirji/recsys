package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作业 swing:基于 user_behavior 正反馈做 Swing 物品相似度,
 * 每个物品取 TopK 相似物品写 Redis {@code swing:{itemId}} ZSet,供 {@code SwingRecaller} 召回。
 *
 * <p>算法(Swing):一对物品 (i,j) 的相似度由"同时交互过 i 和 j 的用户对"贡献,
 * 每个用户对 (u,v) 的贡献被其兴趣重叠度惩罚:
 * <pre>
 *   swing(i,j) = Σ_{u,v ∈ U(i)∩U(j), u&lt;v} 1 / (α + |I(u) ∩ I(v)|)
 * </pre>
 * 直觉:两个兴趣高度重叠的用户共同点了 i、j,说服力弱(他们什么都一起点);
 * 兴趣差异大的两人都点了 i、j,才更说明 i、j 真的相关。比 ItemCF 更抗热门污染。
 *
 * <p>实现:枚举"共现过的用户对",对每对算其交互物品交集 S,|S|≥2 时为 S 中每个物品对累加权重。
 * 复杂度受 max-user-items / max-overlap 截断控制。
 * 参数:--min-rating(默认 4.0)、--topk(默认 50)、--alpha(默认 1.0)、
 * --max-user-items(默认 500)、--max-overlap(默认 200,跳过重叠过大的用户对控规模)。
 */
@Component
public class SwingJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(SwingJob.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public SwingJob(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public String name() {
        return "swing";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        double minRating = doubleArg(args, "min-rating", 4.0);
        int topK = intArg(args, "topk", 50);
        double alpha = doubleArg(args, "alpha", 1.0);
        int maxUserItems = intArg(args, "max-user-items", 500);
        int maxOverlap = intArg(args, "max-overlap", 200);

        // 1. 正反馈:user -> Set<item>(用 Set 便于求交集);item -> List<user>(找共现用户对)
        Long maxTs = BehaviorQuery.maxTs(args);
        String bt = BehaviorQuery.table(args);   // #2
        Map<Long, Set<Long>> userItems = loadUserItems(bt, minRating, maxUserItems, maxTs);
        log.info("正反馈用户数 {};min-rating={}, topk={}, alpha={}, max-ts={}", userItems.size(), minRating,
                topK, alpha, maxTs == null ? "全量" : maxTs);
        if (userItems.isEmpty()) {
            log.warn("无正反馈数据,先跑 --job=import-behavior");
            return;
        }
        Map<Long, List<Long>> itemUsers = new HashMap<>();
        for (var e : userItems.entrySet()) {
            for (long it : e.getValue()) {
                itemUsers.computeIfAbsent(it, k -> new ArrayList<>()).add(e.getKey());
            }
        }

        // 2. 枚举共现用户对(去重),累加 Swing 相似度
        Map<Long, Map<Long, Double>> swing = new HashMap<>();
        Set<Long> seenPair = new HashSet<>();
        long pairs = 0;
        for (List<Long> users : itemUsers.values()) {
            for (int a = 0; a < users.size(); a++) {
                for (int b = a + 1; b < users.size(); b++) {
                    long u = users.get(a);
                    long v = users.get(b);
                    long lo = Math.min(u, v);
                    long hi = Math.max(u, v);
                    if (!seenPair.add(lo * 1_000_003L + hi)) {
                        continue; // 该用户对已处理
                    }
                    List<Long> overlap = intersect(userItems.get(lo), userItems.get(hi));
                    if (overlap.size() < 2 || overlap.size() > maxOverlap) {
                        continue;
                    }
                    double w = 1.0 / (alpha + overlap.size());
                    for (int x = 0; x < overlap.size(); x++) {
                        long i = overlap.get(x);
                        Map<Long, Double> row = swing.computeIfAbsent(i, k -> new HashMap<>());
                        for (int y = 0; y < overlap.size(); y++) {
                            if (x == y) {
                                continue;
                            }
                            row.merge(overlap.get(y), w, Double::sum);
                        }
                    }
                    pairs++;
                }
            }
        }
        log.info("处理共现用户对 {};物品数 {};开始取 TopK 写 Redis", pairs, swing.size());

        // 3. 每个物品取 TopK 写 swing:{itemId}
        int written = writeTopK(swing, topK);
        log.info("swing 完成:写入 {} 个物品的 swing 倒排到 Redis", written);
    }

    private Map<Long, Set<Long>> loadUserItems(String table, double minRating, int maxUserItems, Long maxTs) {
        Map<Long, Set<Long>> userItems = new HashMap<>();
        jdbc.query(
                BehaviorQuery.positiveFeedbackSql(table, "user_id, item_id", maxTs),
                rs -> {
                    long u = rs.getLong(1);
                    long it = rs.getLong(2);
                    userItems.computeIfAbsent(u, k -> new HashSet<>()).add(it);
                },
                BehaviorQuery.params(minRating, maxTs));
        // 截断超活跃用户控规模
        userItems.replaceAll((u, items) -> {
            if (items.size() <= maxUserItems) {
                return items;
            }
            return new HashSet<>(new ArrayList<>(items).subList(0, maxUserItems));
        });
        return userItems;
    }

    private static List<Long> intersect(Set<Long> a, Set<Long> b) {
        Set<Long> small = a.size() <= b.size() ? a : b;
        Set<Long> big = small == a ? b : a;
        List<Long> out = new ArrayList<>();
        for (long x : small) {
            if (big.contains(x)) {
                out.add(x);
            }
        }
        return out;
    }

    private int writeTopK(Map<Long, Map<Long, Double>> swing, int topK) {
        List<Long> items = new ArrayList<>(swing.keySet());
        int total = items.size();
        int[] written = {0};
        int chunk = 500;
        for (int start = 0; start < total; start += chunk) {
            int end = Math.min(start + chunk, total);
            List<Long> sub = items.subList(start, end);
            redis.executePipelined((RedisCallback<Object>) connection -> {
                for (long i : sub) {
                    Map<Long, Double> row = swing.get(i);
                    var heap = new java.util.PriorityQueue<Map.Entry<Long, Double>>(
                            java.util.Comparator.comparingDouble(Map.Entry::getValue));
                    for (var e : row.entrySet()) {
                        heap.offer(e);
                        if (heap.size() > topK) {
                            heap.poll();
                        }
                    }
                    if (heap.isEmpty()) {
                        continue;
                    }
                    byte[] key = RedisKeys.swing(i).getBytes(StandardCharsets.UTF_8);
                    connection.keyCommands().del(key);
                    for (var e : heap) {
                        connection.zSetCommands().zAdd(key, e.getValue(),
                                String.valueOf(e.getKey()).getBytes(StandardCharsets.UTF_8));
                    }
                    written[0]++;
                }
                return null;
            });
        }
        return written[0];
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static double doubleArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }
}
