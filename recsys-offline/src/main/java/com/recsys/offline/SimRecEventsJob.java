package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 作业 sim-rec-events(<b>教学用</b>,仿 {@code sim-ad-events}):为<b>曝光日志闭环</b>造一批带
 * <b>位置偏置</b>的推荐曝光 + 点击,写 {@code user_behavior}(IMPRESSION/CLICK/LIKE,带 {@code position})。
 * 非真实流量 —— 目的是让 {@code gen-samples-impr} 有真实负样本 + 真实位次可用,从而让
 * <b>PAL 位置去偏</b>能被观察到效果(否则评分派生样本里 position 恒 0、PAL 休眠)。
 *
 * <p><b>点击生成模型</b>(刻意让点击依赖位次,使位置效应可识别):
 * <pre>
 *   P(click) = examine(position) × relevance
 *   examine(pos) = decay^(pos-1)        // 位次越靠后越不易被看到(位置偏置的根源)
 *   relevance    ~ U(0.05, 0.45)         // 与位次<b>独立</b>的物品相关性
 * </pre>
 * 点击后以 {@code likeRate} 概率再产生 LIKE(转化,喂 ESMM 的 like 标签)。曝光时间设为"近期",
 * 晚于历史 MovieLens 评分,故 {@code gen-samples-impr} 的 as-of 特征/序列由历史评分填充、无穿越。
 *
 * <p>参数:--sessions(默认 5000)、--slate(每次展示条数=位次数,默认 10)、--users(默认 610)、
 * --decay(位置衰减,默认 0.85)、--like-rate(默认 0.3)、--seed(默认 42)、--clear(先清空既有 sim 行)。
 */
@Component
public class SimRecEventsJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(SimRecEventsJob.class);

    private final JdbcTemplate jdbc;

    public SimRecEventsJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "sim-rec-events";
    }

    @Override
    public void run(ApplicationArguments args) {
        int sessions = intArg(args, "sessions", 5000);
        int slate = intArg(args, "slate", 10);
        int users = intArg(args, "users", 610);
        double decay = doubleArg(args, "decay", 0.85);
        double likeRate = doubleArg(args, "like-rate", 0.3);
        long seed = (long) doubleArg(args, "seed", 42);

        if (args.containsOption("clear")) {
            int del = jdbc.update("DELETE FROM user_behavior WHERE scene='sim-rec'");
            log.info("已清空既有 sim-rec 行 {} 条", del);
        }

        List<Long> items = jdbc.queryForList(
                "SELECT item_id FROM " + ItemQuery.table(args), Long.class);   // #3:item 读来源表(默认 item)
        if (items.isEmpty()) {
            log.warn("item 表为空;先跑 --job=import-items");
            return;
        }
        // 物品"真实相关性"代理 = 评分热度归一化([0,1]):让点击同时依赖位次与<b>物品特征</b>,
        // 这样 relevance 头能学到 item_pop_norm→点击,PAL 头分担位置偏置(两信号都可识别)。
        Map<Long, Double> relevance = loadRelevanceByPopularity();
        Random rnd = new Random(seed);
        long now = System.currentTimeMillis();

        // 行缓冲:[userId, itemId, action, value, position, tsMillis]
        List<Object[]> rows = new ArrayList<>();
        long clicks = 0, likes = 0, imprs = 0;
        for (int s = 0; s < sessions; s++) {
            long userId = 1 + rnd.nextInt(users);
            // 曝光时间设在近 24h 内,且晚于历史评分;同会话点击稍后
            long imprTs = now - (long) (rnd.nextDouble() * 23 * 3600_000L);
            double examine = 1.0;
            for (int p = 1; p <= slate; p++) {
                long itemId = items.get(rnd.nextInt(items.size()));
                rows.add(new Object[]{userId, itemId, "IMPRESSION", (double) p, p, imprTs});
                imprs++;
                // 相关性由物品热度驱动(+ 轻噪声),与位次独立 → 两个信号都可被模型识别
                double rel = 0.08 + 0.50 * relevance.getOrDefault(itemId, 0.0) + 0.05 * rnd.nextDouble();
                if (rnd.nextDouble() < examine * rel) {
                    long clickTs = imprTs + 30_000L + (long) (rnd.nextDouble() * 60_000L);
                    rows.add(new Object[]{userId, itemId, "CLICK", 0.0, p, clickTs});
                    clicks++;
                    if (rnd.nextDouble() < likeRate) {
                        rows.add(new Object[]{userId, itemId, "LIKE", 0.0, p, clickTs + 5_000L});
                        likes++;
                    }
                }
                examine *= decay;                                    // 位次越靠后越不易被看到
            }
        }

        jdbc.batchUpdate(
                "INSERT INTO user_behavior(user_id,item_id,action,value,scene,bucket,position,ts) " +
                "VALUES(?,?,?,?, 'sim-rec','sim',?,?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Object[] r = rows.get(i);
                        ps.setLong(1, (Long) r[0]);
                        ps.setLong(2, (Long) r[1]);
                        ps.setString(3, (String) r[2]);
                        ps.setDouble(4, (Double) r[3]);
                        ps.setInt(5, (Integer) r[4]);
                        ps.setTimestamp(6, new Timestamp((Long) r[5]));
                    }

                    @Override
                    public int getBatchSize() {
                        return rows.size();
                    }
                });

        log.info("sim-rec-events 完成(教学用):曝光 {} / 点击 {}(经验 CTR≈{}) / 赞 {};"
                        + "位置衰减 decay={}。下一步:--job=gen-samples-impr → 重训 mmoe/din 看 PAL 位置曲线",
                imprs, clicks, Math.round(10000.0 * clicks / Math.max(1, imprs)) / 10000.0, likes, decay);
    }

    /** 物品评分热度 → [0,1] 归一相关性代理(log 归一,缓解长尾)。 */
    private Map<Long, Double> loadRelevanceByPopularity() {
        Map<Long, Long> cnt = new java.util.HashMap<>();
        jdbc.query("SELECT item_id, count(*) c FROM user_behavior WHERE action='RATING' GROUP BY item_id",
                rs -> {
                    cnt.put(rs.getLong("item_id"), rs.getLong("c"));
                });
        double lnMax = Math.log1p(cnt.values().stream().mapToLong(Long::longValue).max().orElse(1));
        Map<Long, Double> rel = new java.util.HashMap<>();
        for (var e : cnt.entrySet()) {
            rel.put(e.getKey(), lnMax > 0 ? Math.log1p(e.getValue()) / lnMax : 0.0);
        }
        return rel;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static double doubleArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }
}
