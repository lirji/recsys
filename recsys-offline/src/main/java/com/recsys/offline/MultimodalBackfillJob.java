package com.recsys.offline;

import com.pgvector.PGvector;
import com.recsys.common.embedding.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * 作业 backfill-multimodal:把物品海报图向量与文本向量融合后写回 {@code item_embedding},让向量召回具备多模态信号。
 *
 * <p>对每个「既有文本向量、又有海报图」的物品:
 * <pre>fused = L2normalize( α · textVec + (1-α) · imageVec )</pre>
 * imageVec 由 {@link EmbeddingClient#embedImage} 产出(与文本同 {@link EmbeddingClient#dimension()} 维、同空间),
 * 融合后仍是 768 维、原样进 {@code item_embedding}(pgvector 召回零改动)。model 标记加 {@code +img} 便于追溯/灰度。
 *
 * <p><b>海报来源</b>:本地目录 {@code --poster-dir},文件名 {@code {itemId}.jpg|jpeg|png}(MovieLens 可用 links.csv
 * 的 tmdbId 拉 TMDB 海报,另行准备)。无海报的物品保持纯文本向量不变。
 * <b>降级</b>:当前 EmbeddingClient 不支持多模态(如 gemini-embedding-001 纯文本)→ 首个即抛
 * UnsupportedOperationException,作业提示换多模态模型后退出,不动任何数据。
 *
 * <p>参数:--poster-dir(默认 posters)、--alpha(文本权重,默认 0.5)、--limit(0=全部)。
 */
@Component
public class MultimodalBackfillJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(MultimodalBackfillJob.class);
    private static final String[] EXTS = {"jpg", "jpeg", "png"};

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddingClient;

    public MultimodalBackfillJob(@org.springframework.beans.factory.annotation.Qualifier("derivedJdbc")
                                 JdbcTemplate jdbc, EmbeddingClient embeddingClient) {   // #3:item_embedding 走派生库
        this.jdbc = jdbc;
        this.embeddingClient = embeddingClient;
    }

    @Override
    public String name() {
        return "backfill-multimodal";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String posterDir = strArg(args, "poster-dir", "posters");
        double alpha = doubleArg(args, "alpha", 0.5);
        int limit = intArg(args, "limit", 0);

        File dir = new File(posterDir);
        if (!dir.isDirectory()) {
            log.warn("海报目录不存在:{}(放入 {{itemId}}.jpg/png 后重跑;MovieLens 用 tmdbId 拉 TMDB 海报)",
                    dir.getAbsolutePath());
            return;
        }
        // 只处理有文本向量的物品(融合的另一半),避免给无向量物品凭空造多模态向量
        List<Long> withText = jdbc.queryForList("SELECT item_id FROM item_embedding", Long.class);
        if (withText.isEmpty()) {
            log.warn("item_embedding 为空,先跑 backfill-embedding");
            return;
        }
        log.info("backfill-multimodal 开始:海报目录={}, α(文本)={}, 有文本向量物品={}",
                dir.getAbsolutePath(), alpha, withText.size());

        int fused = 0, noPoster = 0, done = 0;
        boolean multimodalOk = true;
        for (long itemId : withText) {
            File poster = findPoster(dir, itemId);
            if (poster == null) {
                noPoster++;
                continue;
            }
            float[] textVec = loadEmbedding(itemId);
            if (textVec == null) {
                continue;
            }
            float[] imageVec;
            try {
                imageVec = embeddingClient.embedImage(Files.readAllBytes(poster.toPath()));
            } catch (UnsupportedOperationException e) {
                multimodalOk = false;
                log.warn("当前 EmbeddingClient({}) 不支持多模态 embedding —— 换用多模态模型/provider 后重跑;"
                        + "本作业未改动任何数据。", embeddingClient.modelName());
                break;
            }
            if (imageVec.length != textVec.length) {
                log.warn("图像向量维度 {} 与文本 {} 不一致,跳过 item {}", imageVec.length, textVec.length, itemId);
                continue;
            }
            float[] merged = fuse(textVec, imageVec, alpha);
            upsert(itemId, merged);
            fused++;
            if (++done % 200 == 0) {
                log.info("  已融合 {} 个物品向量...", fused);
            }
            if (limit > 0 && fused >= limit) {
                break;
            }
        }
        if (multimodalOk) {
            log.info("backfill-multimodal 完成:融合 {} 个(无海报跳过 {});item_embedding 已更新为多模态向量。",
                    fused, noPoster);
        }
    }

    private static float[] fuse(float[] text, float[] image, double alpha) {
        float[] out = new float[text.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) (alpha * text[i] + (1 - alpha) * image[i]);
        }
        // L2 归一化(便于 pgvector 余弦)
        double norm = 0;
        for (float v : out) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) (out[i] / norm);
            }
        }
        return out;
    }

    private File findPoster(File dir, long itemId) {
        for (String ext : EXTS) {
            File f = new File(dir, itemId + "." + ext);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    private float[] loadEmbedding(long itemId) {
        List<String> rows = jdbc.query(
                "SELECT embedding::text FROM item_embedding WHERE item_id=?",
                (rs, n) -> rs.getString(1), itemId);
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        String s = rows.get(0).replace("[", "").replace("]", "");
        String[] parts = s.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i].trim());
        }
        return v;
    }

    private void upsert(long itemId, float[] vec) {
        PGvector pv = new PGvector(vec);
        String model = embeddingClient.modelName() + "+img";
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "UPDATE item_embedding SET embedding=?, model=? WHERE item_id=?");
            ps.setObject(1, pv);
            ps.setString(2, model);
            ps.setLong(3, itemId);
            return ps;
        });
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static double doubleArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }

    private static String strArg(ApplicationArguments a, String k, String def) {
        return a.containsOption(k) ? a.getOptionValues(k).get(0) : def;
    }
}
