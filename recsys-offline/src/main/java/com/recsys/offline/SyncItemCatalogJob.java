package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.OfflineSourceSessions;
import com.recsys.offline.persistence.mapper.primary.ItemLocalMapper;
import com.recsys.offline.persistence.mapper.source.ItemCopySourceMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 作业 sync-item-catalog(#3 content 上下文物理拆库):把 content 上下文的权威 {@code item} 目录复制到
 * rec-serving <b>自有</b>本地读模型 {@code item_local}(主数据源)。逐候选热路径(LEXICAL/TAG/冷启动/rank
 * category 特征)以 {@code recsys.content.item-source=replica} 读 item_local,不再直读 {@code item}(DB-per-service)。
 *
 * <p><b>本地脚手架里这是 CDC/事件摄取的替身</b>:生产应由 Debezium CDC 或 {@code item-events} 消费者维护。
 * 镜像 {@link SyncBehaviorLogJob},差异:{@code item} <b>可变</b>(title/category/popularity 会改),故用
 * <b>全量 upsert</b>(ON CONFLICT DO UPDATE 覆盖全列)而非 behavior 的 watermark 增量追加。
 *
 * <p>参数:
 * <ul>
 *   <li>{@code --full}:先 TRUNCATE 全量重灌(清掉源已删除的 item);否则全量 upsert(insert 新 + update 改)。</li>
 *   <li><b>{@code --source-db=<db>}</b>:content 物理拆库后,从<b>另一个库</b>(如 {@code recsys_content})读 {@code item}。
 *       不传=同库(读主数据源的 item)。跨库时 Postgres 不支持 {@code INSERT...SELECT},故"从源读 → 批量 upsert
 *       主库 item_local"(CDC 式),连接参数复用 PG_HOST/PORT/USER/PASSWORD。</li>
 * </ul>
 */
@Component
public class SyncItemCatalogJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(SyncItemCatalogJob.class);

    private static final int COPY_BATCH_SIZE = 1000;
    private final ItemLocalMapper persistence;
    private final DataSource dataSource;

    /** Mapper写入与bootstrap复用实际主源，外层事务仍绑定同一资源。 */
    public SyncItemCatalogJob(@Qualifier("sqlSessionTemplate") SqlSessionTemplate sessions) {
        this.persistence = sessions.getMapper(ItemLocalMapper.class);
        this.dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
    }

    @Override
    public String name() {
        return "sync-item-catalog";
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureTable();
        if (args.containsOption("full")) {
            persistence.truncate();
            log.info("sync-item-catalog: --full 全量重灌,已清空 item_local");
        }
        String sourceDb = args.containsOption("source-db")
                ? args.getOptionValues("source-db").get(0).trim() : null;

        int n;
        if (sourceDb == null) {
            // 同库快路径:INSERT...SELECT + 全量 upsert
            n = persistence.copyLocal();
        } else {
            // 跨库(content 独立库):从源读 item → 主库 item_local 批量 upsert(CDC 式)
            n = crossDbCopy(sourceDb);
        }
        Long total = persistence.count();
        log.info("sync-item-catalog: source={} upsert {} 行 → item_local(总 {} 行)",
                sourceDb == null ? "(主库同库)" : sourceDb, n, total);
    }

    /** 源数组读取为独立值，目标TypeHandler重建PG数组，避免跨连接复用JDBC Array。 */
    private int crossDbCopy(String sourceDb) {
        var source = buildSourceSessions(sourceDb).getMapper(ItemCopySourceMapper.class);
        var rows = source.items();
        for (int offset = 0; offset < rows.size(); offset += COPY_BATCH_SIZE) {
            persistence.upsertRows(rows.subList(offset, Math.min(offset + COPY_BATCH_SIZE, rows.size())));
        }
        return rows.size();
    }

    /** 固定源XML不进入主源扫描；包内入口供独占数据源测试替换。 */
    SqlSessionTemplate buildSourceSessions(String sourceDb) {
        return OfflineSourceSessions.create(sourceDb, "classpath*:mapper/offline/source/ItemCopySourceMapper.xml");
    }

    /** 原防御性建表和GIN保留，生成列继续由PG计算而非Java复制。 */
    private void ensureTable() {
        new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/item-local.sql")).execute(dataSource);
    }
}
