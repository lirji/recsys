package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.util.List;

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

    private static final String COLS = "id,user_id,item_id,action,value,scene,bucket,ts,position";

    private final JdbcTemplate jdbc;

    public SyncBehaviorLogJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "sync-behavior-log";
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureTable();
        if (args.containsOption("full")) {
            jdbc.update("TRUNCATE behavior_log");
            log.info("sync-behavior-log: --full 全量重灌,已清空 behavior_log");
        }
        String sourceDb = args.containsOption("source-db")
                ? args.getOptionValues("source-db").get(0).trim() : null;
        Long since = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM behavior_log", Long.class);

        int n;
        if (sourceDb == null) {
            // 同库快路径(#2):INSERT...SELECT
            n = jdbc.update("INSERT INTO behavior_log(" + COLS + ") SELECT " + COLS
                    + " FROM user_behavior WHERE id > ? ON CONFLICT (id) DO NOTHING", since);
        } else {
            // 跨库(#3):从 behavior 独立库读 → 批量写主库 behavior_log(CDC 式)
            n = crossDbCopy(sourceDb, since);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM behavior_log", Long.class);
        log.info("sync-behavior-log: source={} 从 id>{} 复制 {} 行 → behavior_log(总 {} 行)",
                sourceDb == null ? "(主库同库)" : sourceDb, since, n, total);
    }

    /** 跨库复制:源库(behavior-db)读 user_behavior 新行 → 主库 behavior_log 批量 upsert。 */
    private int crossDbCopy(String sourceDb, long since) {
        JdbcTemplate source = buildSourceJdbc(sourceDb);
        List<Object[]> rows = source.query(
                "SELECT " + COLS + " FROM user_behavior WHERE id > ? ORDER BY id",
                ps -> ps.setLong(1, since),
                (rs, i) -> new Object[]{
                        rs.getLong("id"), rs.getObject("user_id"), rs.getObject("item_id"),
                        rs.getString("action"), rs.getObject("value"), rs.getString("scene"),
                        rs.getString("bucket"), rs.getTimestamp("ts"), rs.getObject("position")});
        if (rows.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate(
                "INSERT INTO behavior_log(" + COLS + ") VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING",
                rows, Math.min(rows.size(), 1000),
                (ps, r) -> {
                    for (int c = 0; c < r.length; c++) {
                        ps.setObject(c + 1, r[c]);
                    }
                });
        return rows.size();
    }

    /** 从 PG_HOST/PORT/USER/PASSWORD + 目标 db 名建一个源读数据源(behavior 独立库)。 */
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

    /** 防御性自建(已建库实例无需重跑 09_behavior_log.sql)。 */
    private void ensureTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS behavior_log (
                    id BIGINT PRIMARY KEY, user_id BIGINT, item_id BIGINT, action TEXT,
                    value DOUBLE PRECISION, scene TEXT, bucket TEXT, ts TIMESTAMP, position INT)""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_behavior_log_user_action ON behavior_log(user_id, action)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_behavior_log_item ON behavior_log(item_id)");
    }
}
