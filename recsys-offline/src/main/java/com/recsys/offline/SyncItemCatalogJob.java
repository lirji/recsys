package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.util.List;

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

    /** 生成列 title_tsv 不参与复制(PG 自算);只复制 6 基列。 */
    private static final String COLS = "item_id,title,category,tags,description,popularity";
    /** ON CONFLICT DO UPDATE 的非主键列(item 可变,每次覆盖)。 */
    private static final String UPSERT_SET =
            "title=EXCLUDED.title,category=EXCLUDED.category,tags=EXCLUDED.tags,"
            + "description=EXCLUDED.description,popularity=EXCLUDED.popularity";

    private final JdbcTemplate jdbc;

    public SyncItemCatalogJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "sync-item-catalog";
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureTable();
        if (args.containsOption("full")) {
            jdbc.update("TRUNCATE item_local");
            log.info("sync-item-catalog: --full 全量重灌,已清空 item_local");
        }
        String sourceDb = args.containsOption("source-db")
                ? args.getOptionValues("source-db").get(0).trim() : null;

        int n;
        if (sourceDb == null) {
            // 同库快路径:INSERT...SELECT + 全量 upsert
            n = jdbc.update("INSERT INTO item_local(" + COLS + ") SELECT " + COLS
                    + " FROM item ON CONFLICT (item_id) DO UPDATE SET " + UPSERT_SET);
        } else {
            // 跨库(content 独立库):从源读 item → 主库 item_local 批量 upsert(CDC 式)
            n = crossDbCopy(sourceDb);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM item_local", Long.class);
        log.info("sync-item-catalog: source={} upsert {} 行 → item_local(总 {} 行)",
                sourceDb == null ? "(主库同库)" : sourceDb, n, total);
    }

    /** 跨库复制:源库(content-db)读 item → 主库 item_local 批量 upsert。 */
    private int crossDbCopy(String sourceDb) {
        JdbcTemplate source = buildSourceJdbc(sourceDb);
        List<Object[]> rows = source.query(
                "SELECT " + COLS + " FROM item",
                (rs, i) -> {
                    Array tagsArr = rs.getArray("tags");
                    String[] tags = tagsArr == null ? new String[0] : (String[]) tagsArr.getArray();
                    return new Object[]{
                            rs.getLong("item_id"), rs.getString("title"), rs.getString("category"),
                            tags, rs.getString("description"), rs.getObject("popularity")};
                });
        if (rows.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate(
                "INSERT INTO item_local(" + COLS + ") VALUES(?,?,?,?,?,?) "
                        + "ON CONFLICT (item_id) DO UPDATE SET " + UPSERT_SET,
                rows, Math.min(rows.size(), 1000),
                (ps, r) -> {
                    ps.setObject(1, r[0]);
                    ps.setObject(2, r[1]);
                    ps.setObject(3, r[2]);
                    // tags 是 text[];Array 对象绑定源连接,须在目标连接重建。
                    ps.setArray(4, ps.getConnection().createArrayOf("text", (String[]) r[3]));
                    ps.setObject(5, r[4]);
                    ps.setObject(6, r[5]);
                });
        return rows.size();
    }

    /** 从 PG_HOST/PORT/USER/PASSWORD + 目标 db 名建一个源读数据源(content 独立库)。 */
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

    /** 防御性自建(已建库实例无需重跑 11_item_local.sql);title_tsv 生成列 + GIN。 */
    private void ensureTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS item_local (
                    item_id BIGINT PRIMARY KEY, title TEXT, category TEXT, tags TEXT[],
                    description TEXT, popularity DOUBLE PRECISION DEFAULT 0,
                    title_tsv tsvector GENERATED ALWAYS AS
                        (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(category,''))) STORED)""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_item_local_title_tsv ON item_local USING gin (title_tsv)");
    }
}
