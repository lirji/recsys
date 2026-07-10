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
 * 作业 user-cf:基于 user_behavior 正反馈做 UserCF(U2U),为每个用户物化一份个性化召回列表,
 * 写 Redis {@code u2u:{userId}} ZSet,供 {@code UserCfRecaller} 在线直接读取。
 *
 * <p>两阶段:
 * <ol>
 *   <li>算 user-user 相似度(共现物品 + IIF 惩罚热门物品):
 *       <pre>sim(u,v) = Σ_{i∈I(u)∩I(v)} 1/log(1+|U(i)|) / sqrt(|I(u)|*|I(v)|)</pre></li>
 *   <li>对每个用户 u 取 TopN 相似用户,汇总相似用户的正反馈物品(sim 加权、排除 u 已交互),
 *       取 TopK 写 {@code u2u:{u}}。</li>
 * </ol>
 * 把"找相似用户 + 汇总物品"的重活放离线,在线只剩一次 ZSet 读,符合在线轻/离线重。
 *
 * <p>参数:--min-rating(默认 4.0)、--topk(每用户召回物品数,默认 50)、
 * --sim-users(每用户取多少相似用户,默认 50)、--max-user-items(默认 500)、
 * --max-item-users(默认 1000,跳过超热门物品的共现以控规模)。
 */
@Component
public class UserCfJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(UserCfJob.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public UserCfJob(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public String name() {
        return "user-cf";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        double minRating = doubleArg(args, "min-rating", 4.0);
        int topK = intArg(args, "topk", 50);
        int simUsers = intArg(args, "sim-users", 50);
        int maxUserItems = intArg(args, "max-user-items", 500);
        int maxItemUsers = intArg(args, "max-item-users", 1000);

        // 1. 正反馈:user -> Set<item>(--max-ts 时只用切分点前的行为,供严格 eval)
        Long maxTs = BehaviorQuery.maxTs(args);
        String bt = BehaviorQuery.table(args);   // #2
        Map<Long, Set<Long>> userItems = loadUserItems(bt, minRating, maxUserItems, maxTs);
        log.info("正反馈用户数 {};min-rating={}, topk={}, sim-users={}, max-ts={}", userItems.size(), minRating,
                topK, simUsers, maxTs == null ? "全量" : maxTs);
        if (userItems.isEmpty()) {
            log.warn("无正反馈数据,先跑 --job=import-behavior");
            return;
        }

        // 2. user-user 共现(IIF 惩罚热门物品)
        Map<Long, List<Long>> itemUsers = new HashMap<>();
        for (var e : userItems.entrySet()) {
            for (long it : e.getValue()) {
                itemUsers.computeIfAbsent(it, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        Map<Long, Map<Long, Double>> cooc = new HashMap<>();
        for (List<Long> users : itemUsers.values()) {
            if (users.size() > maxItemUsers) {
                continue; // 超热门物品几乎对所有用户都共现,信息量低且代价大,跳过
            }
            double iif = 1.0 / Math.log(1.0 + users.size());
            for (int a = 0; a < users.size(); a++) {
                long u = users.get(a);
                Map<Long, Double> row = cooc.computeIfAbsent(u, k -> new HashMap<>());
                for (int b = 0; b < users.size(); b++) {
                    if (a == b) {
                        continue;
                    }
                    row.merge(users.get(b), iif, Double::sum);
                }
            }
        }
        log.info("user-user 共现完成,开始为 {} 个用户生成召回列表", cooc.size());

        // 3. 每个用户:TopN 相似用户 → 汇总其物品(sim 加权,排除已看)→ TopK 写 u2u
        int written = buildAndWrite(userItems, cooc, simUsers, topK);
        log.info("user-cf 完成:写入 {} 个用户的 u2u 召回列表到 Redis", written);
    }

    private int buildAndWrite(Map<Long, Set<Long>> userItems,
                              Map<Long, Map<Long, Double>> cooc,
                              int simUsers, int topK) {
        List<Long> users = new ArrayList<>(cooc.keySet());
        int total = users.size();
        int[] written = {0};
        int chunk = 200;
        for (int start = 0; start < total; start += chunk) {
            int end = Math.min(start + chunk, total);
            List<Long> sub = users.subList(start, end);
            redis.executePipelined((RedisCallback<Object>) connection -> {
                for (long u : sub) {
                    int popU = userItems.get(u).size();
                    // 3a. TopN 相似用户(余弦归一化)
                    var simHeap = new java.util.PriorityQueue<Map.Entry<Long, Double>>(
                            java.util.Comparator.comparingDouble(Map.Entry::getValue));
                    for (var e : cooc.get(u).entrySet()) {
                        int popV = userItems.get(e.getKey()).size();
                        double sim = e.getValue() / Math.sqrt((double) popU * popV);
                        simHeap.offer(Map.entry(e.getKey(), sim));
                        if (simHeap.size() > simUsers) {
                            simHeap.poll();
                        }
                    }
                    if (simHeap.isEmpty()) {
                        continue;
                    }
                    // 3b. 汇总相似用户的物品(sim 加权,排除 u 已交互)
                    Set<Long> seen = userItems.get(u);
                    Map<Long, Double> cand = new HashMap<>();
                    for (var s : simHeap) {
                        double sim = s.getValue();
                        for (long it : userItems.get(s.getKey())) {
                            if (!seen.contains(it)) {
                                cand.merge(it, sim, Double::sum);
                            }
                        }
                    }
                    if (cand.isEmpty()) {
                        continue;
                    }
                    // 3c. TopK 物品写 u2u:{u}
                    var itemHeap = new java.util.PriorityQueue<Map.Entry<Long, Double>>(
                            java.util.Comparator.comparingDouble(Map.Entry::getValue));
                    for (var e : cand.entrySet()) {
                        itemHeap.offer(e);
                        if (itemHeap.size() > topK) {
                            itemHeap.poll();
                        }
                    }
                    byte[] key = RedisKeys.u2u(u).getBytes(StandardCharsets.UTF_8);
                    connection.keyCommands().del(key);
                    for (var e : itemHeap) {
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
        userItems.replaceAll((u, items) -> {
            if (items.size() <= maxUserItems) {
                return items;
            }
            return new HashSet<>(new ArrayList<>(items).subList(0, maxUserItems));
        });
        return userItems;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static double doubleArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }
}
