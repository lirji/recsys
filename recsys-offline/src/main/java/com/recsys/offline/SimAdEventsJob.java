package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 作业 sim-ad-events:**教学用**广告曝光/点击模拟,给 {@link AdCalibrateJob} 喂校准所需的数据。
 *
 * <p><b>非真实流量</b>——本地无真实广告点击日志,这里人造一个"已知真值 + 系统性高估"的场景来演示校准:
 * <ul>
 *   <li>真值点击率 {@code trueCtr ~ U(0.02,0.4)};</li>
 *   <li>模型预估 {@code pctr = trueCtr^0.7}(刻意做成系统性高估,制造校准缺口);</li>
 *   <li>点击 ~ Bernoulli(trueCtr)。</li>
 * </ul>
 * 于是 ad_event 里 pctr 普遍高于经验 CTR,保序回归应学到把 pctr 往下校。生产中应换成真实
 * IMPRESSION/CLICK 日志(本作业仅用于打通校准链路 demo)。
 *
 * <p>参数:--requests(默认 20000 次曝光)、--seed(默认 7)。
 */
@Component
public class SimAdEventsJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(SimAdEventsJob.class);

    private final JdbcTemplate jdbc;

    public SimAdEventsJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "sim-ad-events";
    }

    @Override
    public void run(ApplicationArguments args) {
        int requests = intArg(args, "requests", 20000);
        long seed = intArg(args, "seed", 7);
        Long maxAdId = jdbc.queryForObject("SELECT COALESCE(MAX(ad_id),0) FROM ad", Long.class);
        if (maxAdId == null || maxAdId == 0) {
            log.warn("无广告,先跑 --job=seed-ads");
            return;
        }
        Random rnd = new Random(seed);

        List<Object[]> impressions = new ArrayList<>();
        List<Object[]> clicks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String reqId = "sim-" + UUID.randomUUID();
            long userId = 1 + rnd.nextInt(600);
            long adId = 1 + (long) (rnd.nextDouble() * maxAdId);
            int position = 1 + rnd.nextInt(3);
            double trueCtr = 0.02 + 0.38 * rnd.nextDouble();
            double pctr = Math.pow(trueCtr, 0.7);                 // 系统性高估
            double charged = round4(0.1 + rnd.nextDouble());      // 模拟 GSP 价(元)
            impressions.add(new Object[]{reqId, userId, adId, position, pctr, charged});
            if (rnd.nextDouble() < trueCtr) {
                clicks.add(new Object[]{reqId, userId, adId});
            }
        }

        jdbc.batchUpdate(
                "INSERT INTO ad_event(request_id,query,user_id,ad_id,position,event_type," +
                "pctr,pctr_calib,charged_price) VALUES(?, 'sim', ?,?,?, 'IMPRESSION', ?,?,?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Object[] r = impressions.get(i);
                        ps.setString(1, (String) r[0]);
                        ps.setLong(2, (Long) r[1]);
                        ps.setLong(3, (Long) r[2]);
                        ps.setInt(4, (Integer) r[3]);
                        ps.setDouble(5, (Double) r[4]);  // pctr
                        ps.setDouble(6, (Double) r[4]);  // pctr_calib(模拟时=原始,未校准)
                        ps.setDouble(7, (Double) r[5]);  // charged_price
                    }

                    @Override
                    public int getBatchSize() {
                        return impressions.size();
                    }
                });

        jdbc.batchUpdate(
                "INSERT INTO ad_event(request_id,user_id,ad_id,event_type) VALUES(?,?,?, 'CLICK')",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Object[] r = clicks.get(i);
                        ps.setString(1, (String) r[0]);
                        ps.setLong(2, (Long) r[1]);
                        ps.setLong(3, (Long) r[2]);
                    }

                    @Override
                    public int getBatchSize() {
                        return clicks.size();
                    }
                });

        log.info("sim-ad-events 完成(教学用):曝光 {} / 点击 {}(经验 CTR≈{}),可跑 --job=ad-calibrate",
                impressions.size(), clicks.size(), round4((double) clicks.size() / impressions.size()));
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }
}
