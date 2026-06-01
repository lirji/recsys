package com.recsys.offline;

import com.recsys.content.ContentService;
import com.recsys.content.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 作业 import-items:读取 MovieLens(ml-latest-small)写入 item 表。
 * - movies.csv: movieId,title,genres  → item(title, category=首个genre, tags=genres, description)
 * - ratings.csv: 统计每部电影评分数作为 popularity
 * 数据目录不存在时自动从官方地址下载并解压。
 */
@Component
public class ImportItemsJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(ImportItemsJob.class);
    private static final String DOWNLOAD_URL =
            "https://files.grouplens.org/datasets/movielens/ml-latest-small.zip";

    private final ContentService contentService;

    @Value("${recsys.offline.movielens-path:./data/ml-latest-small}")
    private String movielensPath;

    public ImportItemsJob(ContentService contentService) {
        this.contentService = contentService;
    }

    @Override
    public String name() {
        return "import-items";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path dir = Path.of(movielensPath);
        Path moviesCsv = dir.resolve("movies.csv");
        if (!Files.exists(moviesCsv)) {
            log.info("未找到 {},开始下载 MovieLens...", moviesCsv);
            downloadAndUnzip(dir);
        }

        // 1. 统计 popularity(评分数)
        Map<Long, Integer> ratingCount = countRatings(dir.resolve("ratings.csv"));

        // 2. 读 movies.csv 并写库
        int imported = 0;
        try (BufferedReader br = Files.newBufferedReader(moviesCsv, StandardCharsets.UTF_8)) {
            String header = br.readLine(); // 跳过表头
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = parseCsvLine(line);
                if (cols.length < 3) {
                    continue;
                }
                long movieId = Long.parseLong(cols[0].trim());
                String title = cols[1].trim();
                String genresRaw = cols[2].trim();
                List<String> genres = new ArrayList<>();
                if (!genresRaw.isEmpty() && !"(no genres listed)".equals(genresRaw)) {
                    for (String g : genresRaw.split("\\|")) {
                        genres.add(g.trim());
                    }
                }
                String category = genres.isEmpty() ? "Unknown" : genres.get(0);
                String description = title + " | " + String.join(", ", genres);
                double popularity = ratingCount.getOrDefault(movieId, 0);

                contentService.save(new Item(movieId, title, category, genres, description, popularity));
                imported++;
                if (imported % 1000 == 0) {
                    log.info("已导入 {} 部电影...", imported);
                }
            }
        }
        log.info("import-items 完成:共导入 {} 部电影,item 表当前总数 {}", imported, contentService.count());
    }

    private Map<Long, Integer> countRatings(Path ratingsCsv) {
        Map<Long, Integer> counts = new HashMap<>();
        if (!Files.exists(ratingsCsv)) {
            log.warn("无 ratings.csv,popularity 全部置 0");
            return counts;
        }
        try (BufferedReader br = Files.newBufferedReader(ratingsCsv, StandardCharsets.UTF_8)) {
            br.readLine(); // 表头 userId,movieId,rating,timestamp
            String line;
            while ((line = br.readLine()) != null) {
                int firstComma = line.indexOf(',');
                int secondComma = line.indexOf(',', firstComma + 1);
                if (secondComma < 0) {
                    continue;
                }
                long movieId = Long.parseLong(line.substring(firstComma + 1, secondComma).trim());
                counts.merge(movieId, 1, Integer::sum);
            }
        } catch (IOException e) {
            log.warn("读取 ratings.csv 失败: {}", e.getMessage());
        }
        return counts;
    }

    private void downloadAndUnzip(Path targetDir) throws IOException, InterruptedException {
        Path parent = targetDir.getParent() != null ? targetDir.getParent() : Path.of(".");
        Files.createDirectories(parent);
        log.info("下载 {} ...", DOWNLOAD_URL);
        try (InputStream in = URI.create(DOWNLOAD_URL).toURL().openStream();
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // zip 内路径形如 ml-latest-small/movies.csv,直接解压到 parent
                Path out = parent.resolve(entry.getName()).normalize();
                if (!out.startsWith(parent)) {
                    throw new IOException("非法 zip 路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        log.info("下载解压完成 → {}", targetDir);
    }

    /**
     * 解析一行 CSV,支持双引号包裹(MovieLens title 含逗号时会被引号包裹)。
     */
    static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }
}
