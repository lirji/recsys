package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.Reservation;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** ad owner 准备检查点；独立原子语句，不跨报价/预占假装一个事务。 */
public class PreparationRepository {
    public static final String MIGRATION = "db/ad-billing/V004__delivery_preparation.sql";
    public static final String CLICK_MIGRATION = "db/ad-billing/V011__click_preparation.sql";
    private final ChargeMode mode;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public PreparationRepository(JdbcTemplate jdbc, ObjectMapper json) { this(jdbc,json,ChargeMode.IMPRESSION); }
    /** 同一恢复算法按有限模式使用独立表；不允许外部SQL标识符。 */
    public PreparationRepository(JdbcTemplate jdbc,ObjectMapper json,ChargeMode mode) {
        this.jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(5);
        this.json=json.copy();this.mode=java.util.Objects.requireNonNull(mode);
    }
    /** 固定模式供owner装配及路由核验，运行中不变化。 */
    public ChargeMode mode() { return mode; }
    /** 连接池身份用于阻止不同 owner 资源混用。 */
    public javax.sql.DataSource dataSource() { return jdbc.getDataSource(); }

    /** 版本历史只校验，不在应用启动时迁移库。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(mode==ChargeMode.IMPRESSION?MIGRATION:CLICK_MIGRATION).equals(jdbc.queryForObject(
                "SELECT script_sha256 FROM ad_billing_schema_history WHERE version = ?", String.class,mode==ChargeMode.IMPRESSION?4:11))) {
            throw new IllegalStateException("adDb 准备迁移缺失或摘要漂移");
        }
        jdbc.queryForList(sql("SELECT child_id, request_json, result_json FROM ad_delivery_preparation LIMIT 0"));
    }

    /** 时钟只取 owner 数据库，避免实例时钟差改变期限边界。 */
    public Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant(); }

    /** 先保存不可变请求，进程在任一后续步骤失败都能同 ID 恢复。 */
    public void claim(Prepare request) {
        jdbc.update(sql("""
                INSERT INTO ad_delivery_preparation(child_id, operation_id, subject_key, slot, request_json)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """), request.childId(), request.operationId(), request.subject(), request.slot(), write(request));
        var rows = jdbc.query(sql("SELECT request_json FROM ad_delivery_preparation WHERE child_id = ?"),
                (rs, row) -> read(rs.getString(1), Prepare.class), request.childId());
        if (rows.size() != 1 || !request.equals(rows.getFirst())) throw new Conflict();
    }

    /** 认证后查询；未完成和未观察到均返回空，调用方只可继续使用原 ID。 */
    public Optional<Prepared> find(UUID id, String subject) {
        Reservation.validateSubject(subject);
        return jdbc.query(sql("SELECT subject_key, result_json FROM ad_delivery_preparation WHERE child_id = ?"), (rs, row) -> {
            if (!subject.equals(rs.getString(1))) throw new Reservation.AccessDenied();
            return rs.getString(2) == null ? Optional.<Prepared>empty() : Optional.of(read(rs.getString(2), Prepared.class));
        }, id).stream().findFirst().orElse(Optional.empty());
    }

    /** 首个结果获胜，密钥轮换或并发执行也返回数据库里同一 token。 */
    public Prepared complete(Prepared result, String subject) {
        jdbc.update(sql("UPDATE ad_delivery_preparation SET result_json = ?, completed_at = clock_timestamp() WHERE child_id = ? AND result_json IS NULL"),
                write(result), result.childId());
        return find(result.childId(), subject).orElseThrow(() -> new IllegalStateException("准备结果不可查询"));
    }

    /** 已完成原结果先恢复；原命令必须完全一致，不能借密钥轮换改请求。 */
    public Optional<Prepared> original(Prepare request) {
        var rows=jdbc.query(sql("SELECT subject_key,request_json,result_json FROM ad_delivery_preparation WHERE child_id=?"),(rs,n)->{
            if(!request.subject().equals(rs.getString(1))) throw new Reservation.AccessDenied();
            if(!request.equals(read(rs.getString(2),Prepare.class))) throw new Conflict();
            return rs.getString(3)==null?Optional.<Prepared>empty():Optional.of(read(rs.getString(3),Prepared.class));
        },request.childId());
        return rows.stream().findFirst().orElse(Optional.empty());
    }
    private String sql(String statement) {
        return mode==ChargeMode.IMPRESSION?statement:statement.replace("ad_delivery_preparation","ad_click_preparation");
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("准备记录编码失败"); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("准备记录格式不兼容"); }
    }
}
