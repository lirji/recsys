package com.recsys.offline;

import com.pgvector.PGvector;
import com.recsys.common.embedding.EmbeddingClient;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.mapper.derived.ItemEmbeddingMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
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
 * UnsupportedOperationException,作业提示换多模态模型后失败退出；之前成功融合的物品可能已更新。
 *
 * <p>参数:--poster-dir(默认 posters)、--alpha(文本权重,默认 0.5)、--limit(0=全部)。
 */
@Component
public class MultimodalBackfillJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(MultimodalBackfillJob.class);
    private static final int PROGRESS_INTERVAL = 200;
    private static final String[] EXTS = {"jpg", "jpeg", "png"};

    private final ItemEmbeddingMapper mapper;
    private final EmbeddingClient embeddingClient;

    /** 复用派生库向量Mapper，图像计算仍由原EmbeddingClient负责。 */
    public MultimodalBackfillJob(ItemEmbeddingMapper mapper, EmbeddingClient embeddingClient) {
        this.mapper = mapper;
        this.embeddingClient = embeddingClient;
    }

    /** 保持原多模态回填命令名。 */
    @Override
    public String name() {
        return "backfill-multimodal";
    }

    /** 保持成功融合计数与逐物品停止边界，不引入整作业事务。 */
    @Override
    public void run(ApplicationArguments args) {
        try {
            backfill(args);
        } catch (PublicErrorException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new PublicErrorException(ErrorCode.JOB_MULTIMODAL_PARTIAL_FAILED, failure);
        }
    }

    private void backfill(ApplicationArguments args) throws IOException {
        String posterDir = JobArguments.text(args, "poster-dir", "posters");
        double alpha = JobArguments.decimal(args, "alpha", 0.5);
        int limit = JobArguments.integer(args, "limit", 0);
        if (alpha < 0 || alpha > 1 || limit < 0) {
            throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        }

        File dir = new File(posterDir);
        if (!dir.isDirectory()) {
            throw new PublicErrorException(ErrorCode.JOB_MULTIMODAL_NO_INPUT);
        }
        // 只处理有文本向量的物品(融合的另一半),避免给无向量物品凭空造多模态向量
        List<Long> withText = mapper.itemIds();
        if (withText.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_MULTIMODAL_NO_INPUT);
        }
        log.info("backfill-multimodal 开始:海报目录={}, α(文本)={}, 有文本向量物品={}",
                dir.getAbsolutePath(), alpha, withText.size());

        int fused = 0, noPoster = 0, failed = 0;
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
            } catch (UnsupportedOperationException failure) {
                throw new PublicErrorException(ErrorCode.JOB_MULTIMODAL_UNSUPPORTED, failure);
            }
            // 非法响应不能按正常跳过结束；仍允许后续物品产出，最终显式报告部分失败。
            if (!compatibleVectors(textVec, imageVec)) {
                failed++;
                continue;
            }
            float[] merged = fuse(textVec, imageVec, alpha);
            if (upsert(itemId, merged) != 1) {
                // 图像计算期间该行可能被删除；不重新创建，也不能虚增融合数量。
                failed++;
                continue;
            }
            fused++;
            if (fused % PROGRESS_INTERVAL == 0) {
                log.info("  已融合 {} 个物品向量...", fused);
            }
            if (limit > 0 && fused >= limit) {
                break;
            }
        }
        if (failed > 0) throw new PublicErrorException(ErrorCode.JOB_MULTIMODAL_PARTIAL_FAILED);
        if (fused == 0) throw new PublicErrorException(ErrorCode.JOB_MULTIMODAL_NO_INPUT);
        log.info("backfill-multimodal 完成:融合 {} 个(无海报跳过 {});item_embedding 已更新为多模态向量。",
                fused, noPoster);
    }

    private static boolean compatibleVectors(float[] text, float[] image) {
        if (image == null || text.length == 0 || image.length != text.length) return false;
        for (int i = 0; i < text.length; i++) {
            if (!Float.isFinite(text[i]) || !Float.isFinite(image[i])) return false;
        }
        return true;
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
        List<String> rows = mapper.vectorText(itemId);
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

    private int upsert(long itemId, float[] vec) {
        PGvector pv = new PGvector(vec);
        String model = embeddingClient.modelName() + "+img";
        // 原操作仅UPDATE：图像计算期间若该行被删除，不能重新创建已退出的向量。
        return mapper.updateExisting(itemId, pv, model);
    }

}
