package com.recsys.offline;

import com.pgvector.PGvector;
import com.recsys.common.embedding.EmbeddingClient;
import com.recsys.embedding.QuotaExhaustedException;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 作业 backfill-embedding:遍历 item,对每个物品文本调 EmbeddingClient 生成向量,
 * 写入 item_embedding(含 model 字段)。
 *
 * - 物品文本 = title + category + tags + description(见 docs/03 §2)。
 * - 向量已在 EmbeddingClient 内 L2 归一化。
 * - 限流:每条之间小睡,避免触发 Gemini 速率限制。
 * - 幂等:ON CONFLICT 覆盖;可重复跑。支持 --skip-existing 跳过已存在向量。
 */
@Component
public class BackfillEmbeddingJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(BackfillEmbeddingJob.class);

    private final ContentService contentService;
    private final EmbeddingClient embeddingClient;
    private final JdbcTemplate jdbc;

    public BackfillEmbeddingJob(ContentService contentService,
                                EmbeddingClient embeddingClient,
                                JdbcTemplate jdbc) {
        this.contentService = contentService;
        this.embeddingClient = embeddingClient;
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "backfill-embedding";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean skipExisting = args.containsOption("skip-existing");
        long sleepMs = args.containsOption("sleep-ms")
                ? Long.parseLong(args.getOptionValues("sleep-ms").get(0)) : 50L;

        List<Long> ids = contentService.allItemIds();
        log.info("待灌向量物品数: {};skipExisting={}, sleepMs={}, model={}, dim={}",
                ids.size(), skipExisting, sleepMs, embeddingClient.modelName(), embeddingClient.dimension());

        int done = 0, skipped = 0, failed = 0;
        boolean quotaHit = false;
        for (long itemId : ids) {
            try {
                if (skipExisting && embeddingExists(itemId)) {
                    skipped++;
                    continue;
                }
                Item item = contentService.findById(itemId);
                if (item == null) {
                    continue;
                }
                String text = buildText(item);
                float[] vec = embeddingClient.embedText(text);
                if (vec.length != embeddingClient.dimension()) {
                    throw new IllegalStateException("维度不符: 期望 " + embeddingClient.dimension()
                            + " 实得 " + vec.length);
                }
                upsertEmbedding(itemId, vec, embeddingClient.modelName());
                done++;
                if (done % 200 == 0) {
                    log.info("已灌 {} / {} ...", done, ids.size());
                }
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
            } catch (QuotaExhaustedException qe) {
                // 配额耗尽:优雅停止,不空转。下次用 --skip-existing 续跑即可。
                quotaHit = true;
                log.warn("向量化配额耗尽,提前停止。已灌 {};稍后用 --skip-existing 续跑。原因: {}",
                        done, qe.getMessage());
                break;
            } catch (Exception e) {
                failed++;
                log.warn("物品 {} 灌向量失败: {}", itemId, e.getMessage());
            }
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM item_embedding", Long.class);
        log.info("backfill-embedding {}:成功 {},跳过 {},失败 {};item_embedding 总数 {}",
                quotaHit ? "因配额中断" : "完成", done, skipped, failed, total);
    }

    private boolean embeddingExists(long itemId) {
        Integer c = jdbc.queryForObject(
                "SELECT count(*) FROM item_embedding WHERE item_id=?", Integer.class, itemId);
        return c != null && c > 0;
    }

    private void upsertEmbedding(long itemId, float[] vec, String model) {
        PGvector pv = new PGvector(vec);
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO item_embedding(item_id,embedding,model) VALUES(?,?,?) " +
                    "ON CONFLICT(item_id) DO UPDATE SET embedding=EXCLUDED.embedding, model=EXCLUDED.model");
            ps.setLong(1, itemId);
            ps.setObject(2, pv);
            ps.setString(3, model);
            return ps;
        });
    }

    static String buildText(Item item) {
        StringBuilder sb = new StringBuilder();
        if (item.title() != null) {
            sb.append(item.title());
        }
        if (item.category() != null) {
            sb.append(" | ").append(item.category());
        }
        if (item.tags() != null && !item.tags().isEmpty()) {
            sb.append(" | ").append(String.join(", ", item.tags()));
        }
        if (item.description() != null) {
            sb.append(" | ").append(item.description());
        }
        return sb.toString();
    }
}
