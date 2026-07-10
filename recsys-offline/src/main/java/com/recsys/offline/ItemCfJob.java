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
import java.util.List;
import java.util.Map;

/**
 * 作业 item-cf:基于 user_behavior 的正反馈做 ItemCF 物品相似度计算,
 * 每个物品取 TopK 相似物品写 Redis {@code i2i:{itemId}} ZSet,供 I2iRecaller 召回。
 *
 * <p>算法(经典 ItemCF + IUF 活跃用户惩罚):
 * <pre>
 *   sim(i,j) = Σ_{u∈U(i)∩U(j)} 1/log(1+|N(u)|)  /  sqrt(|U(i)| * |U(j)|)
 * </pre>
 * 其中 N(u) 为用户 u 的正反馈物品集。活跃用户(看得多)对相似度贡献被 1/log 衰减,
 * 分母 sqrt(|U(i)|*|U(j)|) 惩罚热门物品,避免一切都跟热门物品相似。
 *
 * <p>正反馈定义:action∈{CLICK,LIKE,PLAY} 恒计入;action=RATING 需 value≥min-rating。
 * 参数:--min-rating(默认 4.0)、--topk(默认 50)、--max-user-items(默认 500,
 * 超活跃用户截断以控共现规模)。
 */
@Component
public class ItemCfJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(ItemCfJob.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public ItemCfJob(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public String name() {
        return "item-cf";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        double minRating = doubleArg(args, "min-rating", 4.0);
        int topK = intArg(args, "topk", 50);
        int maxUserItems = intArg(args, "max-user-items", 500);

        // 1. 加载正反馈:user -> [items](--max-ts 时只用切分点前的行为,供严格 eval)
        Long maxTs = BehaviorQuery.maxTs(args);
        String bt = BehaviorQuery.table(args);   // #2:行为读来源表(默认 user_behavior)
        Map<Long, List<Long>> userItems = loadPositiveFeedback(bt, minRating, maxUserItems, maxTs);
        log.info("正反馈用户数 {};min-rating={}, topk={}, max-ts={}", userItems.size(), minRating, topK,
                maxTs == null ? "全量" : maxTs);
        if (userItems.isEmpty()) {
            log.warn("无正反馈数据,先跑 --job=import-behavior");
            return;
        }

        // 2. 物品流行度 |U(i)| 与 共现矩阵 C[i][j](IUF 加权)
        Map<Long, Integer> itemPop = new HashMap<>();
        Map<Long, Map<Long, Double>> cooc = new HashMap<>();
        for (List<Long> items : userItems.values()) {
            double iuf = 1.0 / Math.log(1.0 + items.size()); // 活跃用户惩罚
            for (Long it : items) {
                itemPop.merge(it, 1, Integer::sum);
            }
            for (int a = 0; a < items.size(); a++) {
                long i = items.get(a);
                Map<Long, Double> row = cooc.computeIfAbsent(i, k -> new HashMap<>());
                for (int b = 0; b < items.size(); b++) {
                    if (a == b) {
                        continue;
                    }
                    row.merge(items.get(b), iuf, Double::sum);
                }
            }
        }
        log.info("物品数 {};开始归一化并取 TopK", itemPop.size());

        // 3. 归一化为余弦相似度 + 取 TopK
        int written = writeTopK(cooc, itemPop, topK);
        log.info("item-cf 完成:写入 {} 个物品的 i2i 倒排到 Redis", written);
    }

    private Map<Long, List<Long>> loadPositiveFeedback(String table, double minRating, int maxUserItems, Long maxTs) {
        Map<Long, List<Long>> userItems = new HashMap<>();
        jdbc.query(
                BehaviorQuery.positiveFeedbackSql(table, "user_id, item_id", maxTs),
                rs -> {
                    long u = rs.getLong(1);
                    long it = rs.getLong(2);
                    userItems.computeIfAbsent(u, k -> new ArrayList<>()).add(it);
                },
                BehaviorQuery.params(minRating, maxTs));
        // 截断超活跃用户,避免共现规模爆炸(O(|N(u)|^2))
        userItems.replaceAll((u, items) ->
                items.size() > maxUserItems ? items.subList(0, maxUserItems) : items);
        return userItems;
    }

    /** 归一化共现为余弦相似度,每个物品取 TopK 写 Redis ZSet,管道批量提交。 */
    private int writeTopK(Map<Long, Map<Long, Double>> cooc, Map<Long, Integer> itemPop, int topK) {
        List<Long> items = new ArrayList<>(cooc.keySet());
        int total = items.size();
        int[] written = {0};
        int chunk = 500;
        for (int start = 0; start < total; start += chunk) {
            int end = Math.min(start + chunk, total);
            List<Long> sub = items.subList(start, end);
            redis.executePipelined((RedisCallback<Object>) connection -> {
                for (long i : sub) {
                    int popI = itemPop.getOrDefault(i, 1);
                    Map<Long, Double> row = cooc.get(i);
                    // 选 TopK 相似物品:小顶堆保留相似度最高的 K 个
                    var heap = new java.util.PriorityQueue<Map.Entry<Long, Double>>(
                            java.util.Comparator.comparingDouble(Map.Entry::getValue));
                    for (var e : row.entrySet()) {
                        double sim = e.getValue() / Math.sqrt((double) popI * itemPop.getOrDefault(e.getKey(), 1));
                        // 借用 entry 承载相似度
                        heap.offer(Map.entry(e.getKey(), sim));
                        if (heap.size() > topK) {
                            heap.poll();
                        }
                    }
                    if (heap.isEmpty()) {
                        continue;
                    }
                    byte[] key = RedisKeys.i2i(i).getBytes(StandardCharsets.UTF_8);
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
