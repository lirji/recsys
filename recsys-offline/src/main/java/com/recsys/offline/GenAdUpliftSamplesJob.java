package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.recsys.offline.persistence.mapper.addb.UpliftSampleMapper;
import com.recsys.offline.persistence.mapper.addb.UpliftSampleMapper.Row;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;

/** A7 因果样本：以随机 assignment 为基表，在其 outcome horizon 内关联独立 conversion fact。 */
@Component
public class GenAdUpliftSamplesJob implements OfflineJob {
    private static final Logger log = LoggerFactory.getLogger(GenAdUpliftSamplesJob.class);
    private final UpliftSampleMapper mapper;
    /** 复用原广告数据源的Mapper会话，保持分配与转化事实的读取边界。 */
    public GenAdUpliftSamplesJob(UpliftSampleMapper mapper) { this.mapper = mapper; }
    /** 保留既有离线命令名。 */
    @Override public String name() { return "gen-ad-uplift-samples"; }

    /** 仅成熟分配可生成标签，按用户最后分配时间整体切分以避免泄漏。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path out = Path.of(JobArguments.text(args, "out", "train/ad_uplift_samples.csv"));
        String objective = JobArguments.text(args, "objective", "purchase");
        int days = JobArguments.integer(args, "days", 0);
        Instant asOf;
        try { asOf = Instant.parse(JobArguments.text(args, "as-of", Instant.now().toString())); }
        catch (java.time.format.DateTimeParseException failure) {
            throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT, failure);
        }
        List<Row> rows = mapper.load(objective, Timestamp.from(asOf), days > 0
                ? Timestamp.from(asOf.minusSeconds(days * 86400L)) : null);
        if (rows.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_NO_MATURE_ASSIGNMENTS);
        }
        // user-level 时间切分：按每个用户最后一次 assignment 排序，整用户进同一侧，杜绝跨 split 泄漏。
        Map<Long, Instant> userLast = new LinkedHashMap<>();
        for (Row row : rows) userLast.merge(row.userId(), row.ts(), (a, b) -> a.isAfter(b) ? a : b);
        List<Long> usersByTime = userLast.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList();
        int trainUsers = Math.max(1, (int) Math.floor(usersByTime.size() * 0.8));
        Set<Long> trainUserIds = new HashSet<>(usersByTime.subList(0, trainUsers));
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("outcome,treatment,propensity,user_id,item_id,ad_id,advertiser_id,pctr,pcvr,quality,relevance,bid,split\n");
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                w.write((r.outcome() ? "1" : "0") + "," + (r.treatment() ? "1" : "0") + "," + r.propensity()
                        + "," + r.userId() + "," + r.itemId() + "," + r.adId() + "," + r.advertiserId()
                        + "," + r.pctr() + "," + r.pcvr() + "," + r.quality() + "," + r.relevance()
                        + "," + r.bid() + "," + (trainUserIds.contains(r.userId()) ? "train" : "valid") + "\n");
            }
        }
        long treated = rows.stream().filter(r -> r.treatment()).count();
        long controlPositive = rows.stream().filter(r -> !r.treatment() && r.outcome()).count();
        log.info("uplift 样本 {} 行 → {};treatment={},control={},control-positive={}",
                rows.size(), out.toAbsolutePath(), treated, rows.size() - treated, controlPositive);
    }

}
