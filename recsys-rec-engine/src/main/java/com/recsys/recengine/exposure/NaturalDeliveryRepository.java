package com.recsys.recengine.exposure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.recsys.common.exposure.*;
import com.recsys.recengine.exposure.NaturalDelivery.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** engine primary PG 负责自然整次结果；短事务租约与单条冻结，不跨 owner 持锁。 */
public class NaturalDeliveryRepository {
    public static final String MIGRATION = "db/engine-delivery/V002__natural_delivery.sql";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public NaturalDeliveryRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(5);
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); transactions.setTimeout(5);
    }
    /** 启动只核对 owner 的 V002，不修改已执行 V001。 */
    public void verifySchema() {
        if (!migrationSha256().equals(jdbc.queryForObject("SELECT script_sha256 FROM engine_delivery_schema_history WHERE version=2",String.class))) {
            throw new IllegalStateException("engine 自然交付迁移缺失或摘要漂移");
        }
        jdbc.queryForList("SELECT operation_id,policy_json,result_json FROM engine_natural_delivery LIMIT 0");
    }
    /** 迁移执行与启动验证读取同一包内文件。 */
    public static String migrationSha256() {
        try (var in = new ClassPathResource(MIGRATION).getInputStream()) {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("自然交付迁移不可读",ex); }
    }
    /** 首次请求固定政策；相同请求恢复不会改变原政策。 */
    public Operation create(Request request, NaturalQualification.Policy policy) {
        return transactions.execute(tx -> {
            jdbc.update("INSERT INTO engine_natural_delivery(operation_id,subject_key,request_json,policy_json) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                    request.operationId(),request.subject(),write(request),write(policy));
            var stored = read(request.operationId(),request.subject()).orElseThrow();
            if (!request.equals(stored.request())) throw new NaturalReceipt.Conflict();
            return stored;
        });
    }
    /** 无写入查询，不因请求到达而重启计算或续期。 */
    public Optional<Operation> find(UUID id, String subject) { return transactions.execute(tx -> read(id,subject)); }
    private Optional<Operation> read(UUID id, String subject) {
        NaturalReceipt.validateSubject(subject);
        return jdbc.query("SELECT * FROM engine_natural_delivery WHERE operation_id=?",(rs,row) -> {
            if (!subject.equals(rs.getString("subject_key"))) throw new NaturalReceipt.AccessDenied();
            return map(rs);
        },id).stream().findFirst();
    }
    /** 领取独立提交后才允许候选和内容远程调用；失效 fence 无权冻结。 */
    public Optional<Operation> acquire(UUID id, String subject) {
        return transactions.execute(tx -> jdbc.query("""
                UPDATE engine_natural_delivery SET fence=fence+1,lease_until=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp()
                WHERE operation_id=? AND subject_key=? AND state='PREPARING' AND (lease_until IS NULL OR lease_until<=clock_timestamp()) RETURNING *
                """,(rs,row)->map(rs),id,subject).stream().findFirst());
    }
    /** 使用数据库时间签发，编码仅保留毫秒，不取应用服务器时钟。 */
    public Instant databaseNow() {
        return transactions.execute(tx -> jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant().truncatedTo(ChronoUnit.MILLIS));
    }
    /** 整次响应原子冻结；超时/提交未知由原 operationId 查询，不交出未提交资格。 */
    public boolean freeze(Operation op, View result) {
        if (result.state()!=State.COMPLETE || !result.operationId().equals(op.request().operationId().toString()) || result.items().size()>op.request().size()) {
            throw new IllegalArgumentException("自然交付冻结结果不匹配");
        }
        String encoded=write(result);
        if(encoded.getBytes(StandardCharsets.UTF_8).length>4194304) throw new IllegalArgumentException("自然交付快照超限");
        return Boolean.TRUE.equals(transactions.execute(tx -> jdbc.update("""
                UPDATE engine_natural_delivery SET state='COMPLETE',result_json=?,lease_until=NULL,updated_at=clock_timestamp()
                WHERE operation_id=? AND subject_key=? AND fence=? AND state='PREPARING' AND lease_until>clock_timestamp()
                """,encoded,op.request().operationId(),op.request().subject(),op.fence())==1));
    }
    /** 失败只释放自己的执行权；不能清除新执行器的租约。 */
    public void relinquish(UUID id,long fence) {
        transactions.executeWithoutResult(tx -> jdbc.update("UPDATE engine_natural_delivery SET lease_until=NULL WHERE operation_id=? AND fence=? AND state='PREPARING'",id,fence));
    }
    private Operation map(ResultSet rs) throws SQLException {
        try {
            String result=rs.getString("result_json");
            return new Operation(json.readValue(rs.getString("request_json"),Request.class),json.readValue(rs.getString("policy_json"),NaturalQualification.Policy.class),
                    State.valueOf(rs.getString("state")),rs.getLong("fence"),result==null?null:json.readValue(result,View.class));
        } catch(java.io.IOException ex) { throw new IllegalStateException("自然交付持久化版本不兼容"); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("自然交付快照编码失败"); }
    }
}
