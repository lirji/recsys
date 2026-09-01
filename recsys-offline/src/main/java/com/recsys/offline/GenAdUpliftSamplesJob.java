package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;

/** A7 因果样本：以随机 assignment 为基表，在其 outcome horizon 内关联独立 conversion fact。 */
@Component
public class GenAdUpliftSamplesJob implements OfflineJob {
    private static final Logger log = LoggerFactory.getLogger(GenAdUpliftSamplesJob.class);
    private final JdbcTemplate jdbc;
    public GenAdUpliftSamplesJob(@Qualifier("adDbJdbc") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public String name() { return "gen-ad-uplift-samples"; }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path out = Path.of(arg(args, "out", "train/ad_uplift_samples.csv"));
        String objective = arg(args, "objective", "purchase");
        int days = Integer.parseInt(arg(args, "days", "0"));
        Instant asOf = args.containsOption("as-of")
                ? Instant.parse(args.getOptionValues("as-of").get(0)) : Instant.now();
        String dayFilter = days > 0 ? " AND a.assigned_at >= ?" : "";
        String sql = "SELECT a.user_id,a.item_id,a.ad_id,a.advertiser_id,a.treatment,a.propensity," +
                "a.pctr,a.pcvr,a.quality,a.relevance,a.bid,a.assigned_at," +
                "EXISTS(SELECT 1 FROM ad_conversion_fact f WHERE f.advertiser_id=a.advertiser_id " +
                "AND f.user_id=a.user_id AND f.objective=? AND f.occurred_at>=a.assigned_at " +
                "AND f.occurred_at<=a.outcome_due_at) AS outcome " +
                "FROM ad_uplift_assignment a WHERE a.outcome_due_at<=?" + dayFilter +
                " ORDER BY a.assigned_at,a.assignment_id";
        List<Row> rows = new ArrayList<>();
        Object[] params = days > 0
                ? new Object[]{objective, Timestamp.from(asOf), Timestamp.from(asOf.minusSeconds(days * 86400L))}
                : new Object[]{objective, Timestamp.from(asOf)};
        jdbc.query(sql, ps -> {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        }, (RowCallbackHandler) rs -> rows.add(new Row(rs.getLong("user_id"), rs.getLong("item_id"), rs.getLong("ad_id"),
                rs.getLong("advertiser_id"), rs.getBoolean("treatment"), rs.getDouble("propensity"),
                rs.getDouble("pctr"), rs.getDouble("pcvr"), rs.getDouble("quality"),
                rs.getDouble("relevance"), rs.getDouble("bid"), rs.getBoolean("outcome"),
                rs.getTimestamp("assigned_at").toInstant())));
        if (rows.isEmpty()) {
            log.warn("没有已成熟 uplift assignment(as-of={});不生成空模型", asOf);
            return;
        }
        // user-level 时间切分：按每个用户最后一次 assignment 排序，整用户进同一侧，杜绝跨 split 泄漏。
        Map<Long, Instant> userLast = new LinkedHashMap<>();
        for (Row row : rows) userLast.merge(row.userId, row.ts, (a, b) -> a.isAfter(b) ? a : b);
        List<Long> usersByTime = userLast.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList();
        int trainUsers = Math.max(1, (int) Math.floor(usersByTime.size() * 0.8));
        Set<Long> trainUserIds = new HashSet<>(usersByTime.subList(0, trainUsers));
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("outcome,treatment,propensity,user_id,item_id,ad_id,advertiser_id,pctr,pcvr,quality,relevance,bid,split\n");
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                w.write((r.outcome ? "1" : "0") + "," + (r.treatment ? "1" : "0") + "," + r.propensity
                        + "," + r.userId + "," + r.itemId + "," + r.adId + "," + r.advertiserId
                        + "," + r.pctr + "," + r.pcvr + "," + r.quality + "," + r.relevance
                        + "," + r.bid + "," + (trainUserIds.contains(r.userId) ? "train" : "valid") + "\n");
            }
        }
        long treated = rows.stream().filter(r -> r.treatment).count();
        long controlPositive = rows.stream().filter(r -> !r.treatment && r.outcome).count();
        log.info("uplift 样本 {} 行 → {};treatment={},control={},control-positive={}",
                rows.size(), out.toAbsolutePath(), treated, rows.size() - treated, controlPositive);
    }

    private static String arg(ApplicationArguments a, String key, String def) {
        List<String> values = a.getOptionValues(key); return values == null || values.isEmpty() ? def : values.get(0);
    }
    private record Row(long userId,long itemId,long adId,long advertiserId,boolean treatment,double propensity,
                       double pctr,double pcvr,double quality,double relevance,double bid,boolean outcome,Instant ts) { }
}
