package com.recsys.recengine.experiment;

import com.recsys.common.constant.ActionType;
import com.recsys.common.dto.RecommendItem;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 曝光埋点:把每次推荐返回的物品作为 {@link ActionType#IMPRESSION} 行为落 {@code user_behavior},
 * 关键是带上分层实验的 {@code bucket} 标签,后续可 {@code GROUP BY bucket} 算分桶 CTR
 * (点击/曝光,点击来自 recsys-behavior 的真实埋点)。
 *
 * <p>异步 fire-and-forget(单线程 executor),不阻塞推荐主链路;失败仅告警不影响返回。
 * SQL 字段口径与 {@code BehaviorService.insert} 一致(action 存大写枚举名)。
 */
@Component
public class ExposureLogger {

    private static final Logger log = LoggerFactory.getLogger(ExposureLogger.class);

    private final JdbcTemplate jdbc;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "exposure-logger");
                t.setDaemon(true);
                return t;
            });

    public ExposureLogger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 异步记录一次曝光:每个推荐物品一行,value=展示排名(1 基)。 */
    public void log(long userId, String scene, String bucket, List<RecommendItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        // 拷贝出落库所需的最小数据,避免异步任务持有上层对象
        List<Long> itemIds = items.stream().map(RecommendItem::itemId).toList();
        executor.submit(() -> insert(userId, scene, bucket, itemIds));
    }

    private void insert(long userId, String scene, String bucket, List<Long> itemIds) {
        try {
            jdbc.batchUpdate(
                    "INSERT INTO user_behavior(user_id,item_id,action,value,scene,bucket) " +
                    "VALUES(?,?,?,?,?,?)",
                    new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                            ps.setLong(1, userId);
                            ps.setLong(2, itemIds.get(i));
                            ps.setString(3, ActionType.IMPRESSION.name());
                            ps.setDouble(4, i + 1); // 展示排名
                            ps.setString(5, scene);
                            ps.setString(6, bucket);
                        }

                        @Override
                        public int getBatchSize() {
                            return itemIds.size();
                        }
                    });
        } catch (Exception e) {
            log.warn("曝光埋点落库失败 user={} bucket={}: {}", userId, bucket, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
