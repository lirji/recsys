package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.OfflineSourceSessions;
import com.recsys.offline.persistence.mapper.primary.AdEventLogMapper;
import com.recsys.offline.persistence.mapper.source.AdEventCopySourceMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 作业 sync-ad-event-log(#3 ad-serving 上下文物理拆库):把 ad-serving 权威 {@code ad_event} 增量幂等复制到
 * 数据平台<b>自有</b>读仓 {@code ad_event_log}(主数据源)。离线分析作业以 {@code --ad-event-table=ad_event_log}
 * 读它,不再直读 ad-serving 的 {@code ad_event}(DB-per-service)。
 *
 * <p><b>本地脚手架里这是 CDC/事件摄取的替身</b>:生产应由 Debezium CDC 或 {@code ad-billing-events} 消费者维护。
 * 镜像 {@link SyncBehaviorLogJob}:ad_event 是追加日志(IMPRESSION/CLICK/CONVERSION 只增),故用 watermark
 * 增量(id > 读仓当前最大 id)+ ON CONFLICT (id) DO NOTHING。
 *
 * <p>参数:
 * <ul>
 *   <li>{@code --full}:先 TRUNCATE 全量重灌。</li>
 *   <li>{@code --source-db=<db>}:ad-serving 物理拆库后从<b>另一个库</b>(如 {@code recsys_ad})读 {@code ad_event}。
 *       不传=同库快路径。跨库时 Postgres 不支持 {@code INSERT...SELECT},故"从源读 → 批量写主库 ad_event_log"。</li>
 * </ul>
 */
@Component
public class SyncAdEventLogJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(SyncAdEventLogJob.class);

    private static final int COPY_BATCH_SIZE = 1000;
    private final AdEventLogMapper persistence;
    private final DataSource dataSource;

    /** 数据平台目标读仓保持原主源，与广告服务权威源会话明确隔离。 */
    public SyncAdEventLogJob(@Qualifier("sqlSessionTemplate") SqlSessionTemplate sessions) {
        this.persistence = sessions.getMapper(AdEventLogMapper.class);
        this.dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
    }

    @Override
    public String name() {
        return "sync-ad-event-log";
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureTable();
        if (args.containsOption("full")) {
            persistence.truncate();
            log.info("sync-ad-event-log: --full 全量重灌,已清空 ad_event_log");
        }
        String sourceDb = args.containsOption("source-db")
                ? args.getOptionValues("source-db").get(0).trim() : null;
        Long since = persistence.watermark();

        int n;
        if (sourceDb == null) {
            n = persistence.copyLocal(since);
        } else {
            n = crossDbCopy(sourceDb, since);
        }
        Long total = persistence.count();
        log.info("sync-ad-event-log: source={} 从 id>{} 复制 {} 行 → ad_event_log(总 {} 行)",
                sourceDb == null ? "(主库同库)" : sourceDb, since, n, total);
    }

    /** 源查询消费完即释放连接，目标沿用原1000行提交边界和冲突忽略。 */
    private int crossDbCopy(String sourceDb, long since) {
        var source = buildSourceSessions(sourceDb).getMapper(AdEventCopySourceMapper.class);
        var rows = source.since(since);
        for (int offset = 0; offset < rows.size(); offset += COPY_BATCH_SIZE) {
            persistence.insertRows(rows.subList(offset, Math.min(offset + COPY_BATCH_SIZE, rows.size())));
        }
        return rows.size();
    }

    /** 原环境参数由源会话工厂复用；固定XML路径不能由命令行传入。 */
    SqlSessionTemplate buildSourceSessions(String sourceDb) {
        return OfflineSourceSessions.create(sourceDb, "classpath*:mapper/offline/source/AdEventCopySourceMapper.xml");
    }

    /** 原防御性建表和三索引提取为资源，参与实际主源上的外层事务。 */
    private void ensureTable() {
        new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/ad-event-log.sql")).execute(dataSource);
    }
}
