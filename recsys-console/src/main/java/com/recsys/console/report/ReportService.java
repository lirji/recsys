package com.recsys.console.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import com.recsys.platform.web.PublicHttpException;
import com.recsys.console.persistence.mapper.primary.ReportMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

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
    private final ReportMapper reports;
    private final ObjectMapper mapper;

    /** 报表Mapper可选，未装配数据库时仍支持原CSV读取入口。 */
    public ReportService(@Value("${recsys.console.eval-dir:../recsys-offline/eval}") String evalDir,
                         @Nullable ReportMapper reports,
                         ObjectMapper mapper) {
        this.evalDir = Path.of(evalDir).toAbsolutePath().normalize();
        this.reports = reports;
        this.mapper = mapper;
        log.info("离线报表: DB(eval_report)优先, 回退目录 {}", this.evalDir);
    }

    /** 有效CSV保持可用；数据源失败又没有回退时，不能把未知目录当作空目录。 */
    public List<ReportFileInfo> list() {
        return catalog().files();
    }

    /** 最新仅指当前可读来源内的顺序；失败不得被说成该类别没有报表。 */
    @Nullable
    public ReportTable latest(String category) {
        Catalog catalog = catalog();
        for (ReportFileInfo info : catalog.files()) {
            if (category.equals(info.category())) return read(info.fileName());
        }
        if (catalog.databaseFailure() != null) throw unavailable(catalog.databaseFailure());
        return null;
    }

    /** 文件名先经过允许列表；坏DB数据仍可用同名CSV恢复，但未知不能变404。 */
    public ReportTable read(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new PublicHttpException(ErrorCode.BAD_REQUEST);
        }
        Exception databaseFailure = null;
        if (reports != null) {
            try {
                ReportTable fromDb = readDb(name);
                if (fromDb != null) return fromDb;
            } catch (IOException | RuntimeException failure) {
                databaseFailure = failure;
                logFallback(failure);
            }
        }
        try {
            return readCsv(name);
        } catch (PublicHttpException absent) {
            if (databaseFailure != null) throw unavailable(databaseFailure);
            throw absent;
        }
    }

    // ---------- DB(eval_report)----------

    /** 仅内部保留DB失败上下文，防止其他类别的CSV误证明当前类别不存在。 */
    private record Catalog(List<ReportFileInfo> files, RuntimeException databaseFailure) { }

    private Catalog catalog() {
        RuntimeException databaseFailure = null;
        if (reports != null) {
            try {
                List<ReportFileInfo> files = reports.list().stream().map(row -> new ReportFileInfo(
                        row.category(), row.name(), row.ts(), row.sizeBytes(),
                        row.createdAt() == null ? 0L : row.createdAt().getTime())).toList();
                if (!files.isEmpty()) return new Catalog(files, null);
            } catch (RuntimeException failure) {
                databaseFailure = failure;
                logFallback(failure);
            }
        }
        List<ReportFileInfo> files = listCsv();
        if (files.isEmpty() && databaseFailure != null) throw unavailable(databaseFailure);
        return new Catalog(files, databaseFailure);
    }

    @Nullable
    private ReportTable readDb(String name) throws IOException {
        var row = reports.read(name);
        if (row == null) return null;
        List<String> columns = mapper.readValue(row.columnsJson(), COLS);
        List<List<String>> rows = mapper.readValue(row.rowsJson(), ROWS);
        if (columns == null || rows == null || columns.stream().anyMatch(java.util.Objects::isNull)
                || rows.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IOException(ErrorCode.CONSOLE_REPORT_READ_FAILED.code());
        }
        return new ReportTable(name, row.category(), columns, rows);
    }

    private static void logFallback(Exception failure) {
        log.debug("{}；尝试CSV回退，failureType={}", ErrorCode.CONSOLE_REPORT_UNAVAILABLE.displayMessage(),
                failure.getClass().getSimpleName());
    }

    private static PublicErrorException unavailable(Throwable failure) {
        return new PublicErrorException(ErrorCode.CONSOLE_REPORT_UNAVAILABLE, failure);
    }

    // ---------- CSV 回退 ----------

    private List<ReportFileInfo> listCsv() {
        if (Files.notExists(evalDir)) return List.of();
        if (!Files.isDirectory(evalDir)) throw unavailable(null);
        try (Stream<Path> files = Files.list(evalDir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .filter(p -> !Files.isDirectory(p))
                    .map(this::toInfo)
                    .sorted(Comparator.comparingLong(ReportFileInfo::modifiedAt).reversed())
                    .toList();
        } catch (IOException | java.io.UncheckedIOException failure) {
            throw unavailable(failure);
        }
    }

    private ReportTable readCsv(String name) {
        Path file = evalDir.resolve(name).normalize();
        // 规范化后必须仍在 eval 目录下(防 ../ 穿越)。
        if (!file.getParent().equals(evalDir) || Files.notExists(file) || Files.isDirectory(file)) {
            throw new PublicHttpException(ErrorCode.NOT_FOUND);
        }
        if (!Files.isRegularFile(file)) throw unavailable(null);
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
        } catch (IOException | RuntimeException failure) {
            throw new PublicErrorException(ErrorCode.CONSOLE_REPORT_READ_FAILED, failure);
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
        } catch (IOException failure) {
            // 无法取得元数据时不能伪造时间与大小，影响最新排序的错误必须可见。
            throw unavailable(failure);
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
