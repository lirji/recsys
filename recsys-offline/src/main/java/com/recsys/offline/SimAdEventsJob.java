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
 * <p><b>延迟转化(docs/05 §6,M6)</b>:曝光/点击在过去 {@code --days} 天内均匀铺开,
 * 每次点击的转化延迟 ~ 指数分布(均值 {@code --conv-delay-days} 天),转化时刻 = 点击时刻 + 延迟。
 * 转化按其<b>真实(可能未来)时刻</b>落库——晚于 now() 的即"尚未到达"(右删失),下游查询用
 * {@code ts <= now()} 只看已观测部分,于是 ad-ocpc 直接数转化会系统性偏低(这正是延迟反馈偏差)。
 * {@code ad-delay} 拟合延迟分布、{@code ad-ocpc --delay-correct} 用完成曲线把它纠偏回来。
 *
 * <p>参数:--requests(默认 20000 次曝光)、--seed(默认 7)、--days(事件铺开窗口,默认 14)、
 * --conv-delay-days(平均转化延迟天数,默认 3)。
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
        int days = intArg(args, "days", 14);
        double meanDelayDays = dblArg(args, "conv-delay-days", 3.0);
        Long maxAdId = jdbc.queryForObject("SELECT COALESCE(MAX(ad_id),0) FROM ad", Long.class);
        if (maxAdId == null || maxAdId == 0) {
            log.warn("无广告,先跑 --job=seed-ads");
            return;
        }
        Random rnd = new Random(seed);

        long now = System.currentTimeMillis();
        long windowMs = (long) days * 86_400_000L;
        double meanDelayMs = meanDelayDays * 86_400_000L;

        List<Object[]> impressions = new ArrayList<>();
        List<Object[]> clicks = new ArrayList<>();
        List<Object[]> conversions = new ArrayList<>();
        long observedConv = 0;                                    // 转化时刻 <= now 的(已到达)
        for (int i = 0; i < requests; i++) {
            String reqId = "sim-" + UUID.randomUUID();
            long userId = 1 + rnd.nextInt(600);
            long adId = 1 + (long) (rnd.nextDouble() * maxAdId);
            int position = 1 + rnd.nextInt(3);
            double trueCtr = 0.02 + 0.38 * rnd.nextDouble();
            double pctr = Math.pow(trueCtr, 0.7);                 // 系统性高估
            double charged = round4(0.1 + rnd.nextDouble());      // 模拟 GSP 价(元)
            long eventTs = now - (long) (rnd.nextDouble() * windowMs);  // 曝光/点击时刻铺在窗口内
            impressions.add(new Object[]{reqId, userId, adId, position, pctr, charged, new Timestamp(eventTs)});
            if (rnd.nextDouble() < trueCtr) {
                clicks.add(new Object[]{reqId, userId, adId, new Timestamp(eventTs)});
                // 点击后按真实 CVR 转化(教学用,给 oCPC 反馈控制 ad-ocpc 喂数据)
                double trueCvr = 0.08 + 0.22 * rnd.nextDouble();
                if (rnd.nextDouble() < trueCvr) {
                    // 指数延迟:delay = -mean·ln(U);转化时刻 = 点击时刻 + 延迟(可能晚于 now = 尚未到达)
                    long delayMs = (long) (-meanDelayMs * Math.log(1.0 - rnd.nextDouble()));
                    long convTs = eventTs + delayMs;
                    conversions.add(new Object[]{reqId, userId, adId, new Timestamp(convTs)});
                    if (convTs <= now) {
                        observedConv++;
                    }
                }
            }
        }

        jdbc.batchUpdate(
                "INSERT INTO ad_event(request_id,query,user_id,ad_id,position,event_type," +
                "pctr,pctr_calib,charged_price,ts) VALUES(?, 'sim', ?,?,?, 'IMPRESSION', ?,?,?,?)",
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
                        ps.setTimestamp(8, (Timestamp) r[6]);
                    }

                    @Override
                    public int getBatchSize() {
                        return impressions.size();
                    }
                });

        jdbc.batchUpdate(
                "INSERT INTO ad_event(request_id,user_id,ad_id,event_type,ts) VALUES(?,?,?, 'CLICK', ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Object[] r = clicks.get(i);
                        ps.setString(1, (String) r[0]);
                        ps.setLong(2, (Long) r[1]);
                        ps.setLong(3, (Long) r[2]);
                        ps.setTimestamp(4, (Timestamp) r[3]);
                    }

                    @Override
                    public int getBatchSize() {
                        return clicks.size();
                    }
                });

        jdbc.batchUpdate(
                "INSERT INTO ad_event(request_id,user_id,ad_id,event_type,ts) VALUES(?,?,?, 'CONVERSION', ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Object[] r = conversions.get(i);
                        ps.setString(1, (String) r[0]);
                        ps.setLong(2, (Long) r[1]);
                        ps.setLong(3, (Long) r[2]);
                        ps.setTimestamp(4, (Timestamp) r[3]);
                    }

                    @Override
                    public int getBatchSize() {
                        return conversions.size();
                    }
                });

        log.info("sim-ad-events 完成(教学用):曝光 {} / 点击 {} / 转化 {}(其中已到达 {},尚未到达 {} = 延迟删失)。"
                        + "经验 CTR≈{}。窗口 {} 天、平均转化延迟 {} 天。"
                        + "可跑 --job=ad-delay(拟合延迟分布)→ --job=ad-ocpc(纠偏调价)、--job=ad-calibrate(校准 pCTR)",
                impressions.size(), clicks.size(), conversions.size(),
                observedConv, conversions.size() - observedConv,
                round4((double) clicks.size() / impressions.size()), days, meanDelayDays);
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static double dblArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }
}
