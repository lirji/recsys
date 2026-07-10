package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.util.List;

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

    private static final String COLS = "id,request_id,query,user_id,ad_id,bidword_id,position,event_type,"
            + "pctr,pctr_calib,ecpm,charged_price,relevance,ad_bucket,ts,creative_id";

    private final JdbcTemplate jdbc;

    public SyncAdEventLogJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "sync-ad-event-log";
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureTable();
        if (args.containsOption("full")) {
            jdbc.update("TRUNCATE ad_event_log");
            log.info("sync-ad-event-log: --full 全量重灌,已清空 ad_event_log");
        }
        String sourceDb = args.containsOption("source-db")
                ? args.getOptionValues("source-db").get(0).trim() : null;
        Long since = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM ad_event_log", Long.class);

        int n;
        if (sourceDb == null) {
            n = jdbc.update("INSERT INTO ad_event_log(" + COLS + ") SELECT " + COLS
                    + " FROM ad_event WHERE id > ? ON CONFLICT (id) DO NOTHING", since);
        } else {
            n = crossDbCopy(sourceDb, since);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ad_event_log", Long.class);
        log.info("sync-ad-event-log: source={} 从 id>{} 复制 {} 行 → ad_event_log(总 {} 行)",
                sourceDb == null ? "(主库同库)" : sourceDb, since, n, total);
    }

    /** 跨库复制:源库(ad-serving 库)读 ad_event 新行 → 主库 ad_event_log 批量 upsert。 */
    private int crossDbCopy(String sourceDb, long since) {
        JdbcTemplate source = buildSourceJdbc(sourceDb);
        List<Object[]> rows = source.query(
                "SELECT " + COLS + " FROM ad_event WHERE id > ? ORDER BY id",
                ps -> ps.setLong(1, since),
                (rs, i) -> new Object[]{
                        rs.getLong("id"), rs.getString("request_id"), rs.getString("query"),
                        rs.getObject("user_id"), rs.getObject("ad_id"), rs.getObject("bidword_id"),
                        rs.getObject("position"), rs.getString("event_type"), rs.getObject("pctr"),
                        rs.getObject("pctr_calib"), rs.getObject("ecpm"), rs.getObject("charged_price"),
                        rs.getObject("relevance"), rs.getString("ad_bucket"), rs.getTimestamp("ts"),
                        rs.getObject("creative_id")});
        if (rows.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate(
                "INSERT INTO ad_event_log(" + COLS + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (id) DO NOTHING",
                rows, Math.min(rows.size(), 1000),
                (ps, r) -> {
                    for (int c = 0; c < r.length; c++) {
                        ps.setObject(c + 1, r[c]);
                    }
                });
        return rows.size();
    }

    private JdbcTemplate buildSourceJdbc(String sourceDb) {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + env("PG_HOST", "localhost") + ":" + env("PG_PORT", "5432") + "/" + sourceDb,
                env("PG_USER", "recsys"), env("PG_PASSWORD", "recsys"));
        ds.setDriverClassName("org.postgresql.Driver");
        return new JdbcTemplate(ds);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    /** 防御性自建(已建库实例无需重跑 14_ad_event_log.sql)。 */
    private void ensureTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ad_event_log (
                    id BIGINT PRIMARY KEY, request_id TEXT, query TEXT, user_id BIGINT, ad_id BIGINT,
                    bidword_id BIGINT, position INT, event_type TEXT, pctr DOUBLE PRECISION,
                    pctr_calib DOUBLE PRECISION, ecpm DOUBLE PRECISION, charged_price DOUBLE PRECISION,
                    relevance DOUBLE PRECISION, ad_bucket TEXT, ts TIMESTAMP, creative_id BIGINT)""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ad_event_log_type_ts ON ad_event_log(event_type, ts)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ad_event_log_ad ON ad_event_log(ad_id, event_type)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ad_event_log_req ON ad_event_log(request_id)");
    }
}
