package com.recsys.console.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 离线报表读取:主读 {@code eval_report} 表(由 offline 作业 {@code publish-reports} 落库),
 * DB 不可用/表无该记录时回退解析本地 {@code eval/*.csv}(优雅降级)。
 *
 * <p>前后端分离配套:控制台后端不再强耦合 offline 的本地文件路径 —— 真正部署时 console 与 offline 可分处不同主机,
 * 报表数据经 DB 流转;本地 CSV 仅作单机/未落库时的回退。
 *
 * <p>安全:文件名必须匹配白名单正则(无路径分隔符/no ..);CSV 回退路径解析后其父目录规范化须等于 eval 目录,杜绝穿越。
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9._-]+\\.csv$");
    private static final TypeReference<List<String>> COLS = new TypeReference<>() { };
    private static final TypeReference<List<List<String>>> ROWS = new TypeReference<>() { };

    // 文件名前缀 → 报表分类。顺序不敏感(精确前缀)。
    private static final Map<String, String> PREFIX = new LinkedHashMap<>();
    static {
        PREFIX.put("metrics-", "eval");
        PREFIX.put("ab-report-", "ab-report");
        PREFIX.put("ad-report-", "ad-report");
        PREFIX.put("data-quality-", "data-quality");
        PREFIX.put("ad-quality-", "ad-quality");
    }

    private final Path evalDir;
    @Nullable
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ReportService(@Value("${recsys.console.eval-dir:../recsys-offline/eval}") String evalDir,
                         @Nullable JdbcTemplate jdbc,
                         ObjectMapper mapper) {
        this.evalDir = Path.of(evalDir).toAbsolutePath().normalize();
        this.jdbc = jdbc;
        this.mapper = mapper;
        log.info("离线报表: DB(eval_report)优先, 回退目录 {}", this.evalDir);
    }

    public List<ReportFileInfo> list() {
        List<ReportFileInfo> fromDb = listDb();
        if (fromDb != null && !fromDb.isEmpty()) {
            return fromDb;
        }
        return listCsv();
    }

    public ReportTable read(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法文件名");
        }
        ReportTable fromDb = readDb(name);
        if (fromDb != null) {
            return fromDb;
        }
        return readCsv(name);
    }

    // ---------- DB(eval_report)----------

    @Nullable
    private List<ReportFileInfo> listDb() {
        if (jdbc == null) {
            return null;
        }
        try {
            return jdbc.query(
                    "SELECT category, name, ts, size_bytes, created_at FROM eval_report ORDER BY created_at DESC",
                    (rs, i) -> new ReportFileInfo(
                            rs.getString("category"), rs.getString("name"), rs.getString("ts"),
                            rs.getLong("size_bytes"),
                            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").getTime() : 0L));
        } catch (Exception e) {
            log.debug("eval_report 读取失败,回退 CSV: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    private ReportTable readDb(String name) {
        if (jdbc == null) {
            return null;
        }
        try {
            return jdbc.query(
                    "SELECT category, columns_json, rows_json FROM eval_report WHERE name = ?",
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        try {
                            List<String> columns = mapper.readValue(rs.getString("columns_json"), COLS);
                            List<List<String>> rows = mapper.readValue(rs.getString("rows_json"), ROWS);
                            return new ReportTable(name, rs.getString("category"), columns, rows);
                        } catch (IOException je) {
                            throw new IllegalStateException("解析 eval_report JSON 失败", je);
                        }
                    }, name);
        } catch (Exception e) {
            log.debug("eval_report 单条读取失败,回退 CSV: {}", e.getMessage());
            return null;
        }
    }

    // ---------- CSV 回退 ----------

    private List<ReportFileInfo> listCsv() {
        if (!Files.isDirectory(evalDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(evalDir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .map(this::toInfo)
                    .sorted(Comparator.comparingLong(ReportFileInfo::modifiedAt).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("列出报表目录失败: {}", e.getMessage());
            return List.of();
        }
    }

    private ReportTable readCsv(String name) {
        Path file = evalDir.resolve(name).normalize();
        // 规范化后必须仍在 eval 目录下(防 ../ 穿越)。
        if (!file.getParent().equals(evalDir) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报表不存在: " + name);
        }
        String category = categoryOf(name);
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
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "解析报表失败: " + e.getMessage());
        }
        return new ReportTable(name, category, columns, rows);
    }

    private ReportFileInfo toInfo(Path p) {
        String name = p.getFileName().toString();
        long size = 0L;
        long mtime = 0L;
        try {
            size = Files.size(p);
            mtime = Files.getLastModifiedTime(p).toMillis();
        } catch (IOException ignored) {
            // 用默认 0
        }
        return new ReportFileInfo(categoryOf(name), name, timestampOf(name), size, mtime);
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
}
