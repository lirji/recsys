package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.OfflineSourceSessions;
import com.recsys.offline.persistence.mapper.primary.BehaviorLogMapper;
import com.recsys.offline.persistence.mapper.source.BehaviorCopySourceMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 作业 sync-behavior-log(#2 离线读侧解耦 + #3 跨库):把行为上下文的 {@code user_behavior} 增量幂等复制到
 * offline/数据平台<b>自有</b>读仓 {@code behavior_log}(主数据源)。离线 CF/hot/embedding 作业以
 * {@code --behavior-table=behavior_log} 读它,不再直读 {@code user_behavior}(DB-per-service)。
 *
 * <p><b>本地脚手架里这是 CDC/事件摄取的替身</b>:生产应由 Debezium CDC 或 {@code behavior-events} 消费者维护。
 *
 * <p>参数:
 * <ul>
 *   <li>{@code --full}:先 TRUNCATE 全量重灌;否则增量(id > behavior_log 当前最大 id)。</li>
 *   <li><b>{@code --source-db=<db>}(#3)</b>:behavior 物理拆库后,从<b>另一个库</b>(如 {@code recsys_behavior})
 *       读 {@code user_behavior}。不传=同库(读主数据源的 user_behavior,= #2 行为)。跨库时 Postgres 不支持
 *       {@code INSERT...SELECT},故"从源读 → 批量写主库 behavior_log"(CDC 式),连接参数复用 PG_HOST/PORT/USER/PASSWORD。</li>
 * </ul>
 */
@Component
public class SyncBehaviorLogJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(SyncBehaviorLogJob.class);

    private static final int COPY_BATCH_SIZE = 1000;
    private final BehaviorLogMapper persistence;
    private final DataSource dataSource;

    /** 目标写入和bootstrap严格复用同一个原主数据源及事务资源。 */
    public SyncBehaviorLogJob(@Qualifier("sqlSessionTemplate") SqlSessionTemplate sessions) {
        this.persistence = sessions.getMapper(BehaviorLogMapper.class);
        this.dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
    }

    @Override
    public String name() {
        return "sync-behavior-log";
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureTable();
        if (args.containsOption("full")) {
            persistence.truncate();
            log.info("sync-behavior-log: --full 全量重灌,已清空 behavior_log");
        }
        String sourceDb = args.containsOption("source-db")
                ? args.getOptionValues("source-db").get(0).trim() : null;
        Long since = persistence.watermark();

        int n;
        if (sourceDb == null) {
            // 同库快路径(#2):INSERT...SELECT
            n = persistence.copyLocal(since);
        } else {
            // 跨库(#3):从 behavior 独立库读 → 批量写主库 behavior_log(CDC 式)
            n = crossDbCopy(sourceDb, since);
        }
        Long total = persistence.count();
        log.info("sync-behavior-log: source={} 从 id>{} 复制 {} 行 → behavior_log(总 {} 行)",
                sourceDb == null ? "(主库同库)" : sourceDb, since, n, total);
    }

    /** 源会话先完成读取并关闭连接，再按原1000行边界写目标，避免跨库事务混淆。 */
    private int crossDbCopy(String sourceDb, long since) {
        var source = buildSourceSessions(sourceDb).getMapper(BehaviorCopySourceMapper.class);
        var rows = source.since(since);
        for (int offset = 0; offset < rows.size(); offset += COPY_BATCH_SIZE) {
            persistence.insertRows(rows.subList(offset, Math.min(offset + COPY_BATCH_SIZE, rows.size())));
        }
        return rows.size();
    }

    /** 保留原PG环境来源；包内入口允许隔离测试替换源，不接触共享库。 */
    SqlSessionTemplate buildSourceSessions(String sourceDb) {
        return OfflineSourceSessions.create(sourceDb, "classpath*:mapper/offline/source/BehaviorCopySourceMapper.xml");
    }

    /** 原防御性建表和旧列升级提取为资源；执行顺序与外层事务参与方式保持。 */
    private void ensureTable() {
        new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/behavior-log.sql")).execute(dataSource);
    }
}
