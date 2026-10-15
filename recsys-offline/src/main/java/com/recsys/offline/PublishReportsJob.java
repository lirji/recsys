package com.recsys.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.mapper.primary.ReportPublicationMapper;
import org.springframework.stereotype.Component;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 作业 publish-reports:把 eval/*.csv 报表解析后写入 eval_report 表,解耦控制台读取。
 *
 * <p>背景(前后端分离配套):recsys-console 原先直接读 offline 的本地 eval/*.csv(跨进程共享文件系统路径)。
 * 本作业把这些 CSV 解析成 {@code {columns, rows}} 落库,console 改从表读(表缺失/无数据时回退 CSV)。
 * 幂等:按文件名唯一键 {@code ON CONFLICT DO NOTHING},重复运行只补新文件。
 *
 * <p>参数:--eval-dir(默认 "eval",相对进程 CWD,与各报表作业的 OUT_DIR 一致)。
 */
@Component
public class PublishReportsJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(PublishReportsJob.class);

    // 文件名前缀 → 分类(与 recsys-console 的 ReportService 保持一致)。
    private static final Map<String, String> PREFIX = new LinkedHashMap<>();
    static {
        PREFIX.put("metrics-", "eval");
        PREFIX.put("ab-report-", "ab-report");
        PREFIX.put("ad-report-", "ad-report");
        PREFIX.put("data-quality-", "data-quality");
        PREFIX.put("ad-quality-", "ad-quality");
    }

    private final ReportPublicationMapper reports;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 沿原主库会话发布报表，单文件幂等和失败处理仍由作业负责。 */
    public PublishReportsJob(ReportPublicationMapper reports) {
        this.reports = reports;
    }

    /** 保留既有离线命令名。 */
    @Override
    public String name() {
        return "publish-reports";
    }

    /** 自举结构后按文件名顺序发布，单文件失败继续下一份，已有文件不覆盖。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        String dir = argValue(args, "eval-dir", "eval");
        ensureTable();

        Path evalDir = Path.of(dir);
        if (!Files.isDirectory(evalDir)) {
            log.warn("报表目录不存在,跳过: {}", evalDir.toAbsolutePath());
            return;
        }

        int published = 0, skipped = 0, failed = 0;
        List<Path> files;
        try (Stream<Path> s = Files.list(evalDir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".csv")).sorted().toList();
        }
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            try {
                List<String> columns = new ArrayList<>();
                List<List<String>> rows = new ArrayList<>();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                     CSVParser parser = CSVParser.parse(reader, CSVFormat.DEFAULT.builder()
                             .setHeader().setSkipHeaderRecord(true).build())) {
                    columns.addAll(parser.getHeaderNames());
                    int width = columns.size();
                    for (CSVRecord rec : parser) {
                        List<String> row = new ArrayList<>(width);
                        for (int i = 0; i < width; i++) {
                            row.add(i < rec.size() ? rec.get(i) : "");
                        }
                        rows.add(row);
                    }
                }
                int n = reports.publishIfAbsent(new ReportPublicationMapper.Report(
                        categoryOf(fileName), fileName, timestampOf(fileName),
                        mapper.writeValueAsString(columns), mapper.writeValueAsString(rows),
                        Files.size(file)));
                if (n > 0) published++; else skipped++;
            } catch (Exception e) {
                failed++;
                log.warn("解析/落库失败 {}: {}", fileName, e.getMessage());
            }
        }
        log.info("publish-reports 完成: 新增 {}, 已存在跳过 {}, 失败 {} (共 {} 个 CSV)",
                published, skipped, failed, files.size());
    }

    private void ensureTable() {
        reports.createTable();
        reports.commentTable();
        reports.commentColumns();
        reports.createIndex();
    }

    private static String categoryOf(String name) {
        for (Map.Entry<String, String> e : PREFIX.entrySet()) {
            if (name.startsWith(e.getKey())) return e.getValue();
        }
        return "other";
    }

    private static String timestampOf(String name) {
        for (String prefix : PREFIX.keySet()) {
            if (name.startsWith(prefix)) {
                return name.substring(prefix.length(), name.length() - ".csv".length());
            }
        }
        return name.substring(0, Math.max(0, name.length() - ".csv".length()));
    }

    private static String argValue(ApplicationArguments args, String key, String def) {
        List<String> vals = args.getOptionValues(key);
        return (vals == null || vals.isEmpty()) ? def : vals.get(0);
    }
}
