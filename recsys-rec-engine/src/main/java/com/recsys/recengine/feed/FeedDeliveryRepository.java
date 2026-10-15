package com.recsys.recengine.feed;

import com.fasterxml.jackson.databind.*;
import com.recsys.common.exposure.NaturalReceipt;
import com.recsys.recengine.feed.FeedDelivery.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** engine primary 的短事务检查点；资格可返回之前必须已冻结完整位置。 */
public class FeedDeliveryRepository {
    public static final String MIGRATION="db/engine-delivery/V003__feed_delivery.sql";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public FeedDeliveryRepository(JdbcTemplate jdbc,ObjectMapper json) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(5);
        this.json=json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        transactions=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); transactions.setTimeout(5);
    }
    /** 仅校验显式执行的本 owner 迁移，缺失拒绝启用。 */
    public void verifySchema() {
        if(!migrationSha256().equals(jdbc.queryForObject("SELECT script_sha256 FROM engine_delivery_schema_history WHERE version=3",String.class))) {
            throw new IllegalStateException("feed 迁移缺失或摘要漂移");
        }
        jdbc.queryForList("SELECT operation_id,settings_json,plan_json FROM engine_feed_delivery LIMIT 0");
    }
    /** 实际脚本摘要用于迁移历史与启动的一致性核对。 */
    public static String migrationSha256() {
        try(var in=new ClassPathResource(MIGRATION).getInputStream()) {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch(java.io.IOException | java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("feed 迁移不可读",ex); }
    }
    /** 并发同 ID 只能保留首次请求和配置，改负载明确冲突。 */
    public Operation create(Request request,Settings settings) {
        return transactions.execute(tx->{
            jdbc.update("INSERT INTO engine_feed_delivery(operation_id,subject_key,request_json,settings_json) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                    request.operationId(),request.subject(),write(request),write(settings));
            var result=read(request.operationId(),request.subject()).orElseThrow();
            if(!request.equals(result.request())) throw new NaturalReceipt.Conflict();
            return result;
        });
    }
    /** 读取不发起 owner 操作，未观察到不能证明前次请求未提交。 */
    public Optional<Operation> find(UUID id,String subject) { return transactions.execute(tx->read(id,subject)); }
    private Optional<Operation> read(UUID id,String subject) {
        NaturalReceipt.validateSubject(subject);
        return jdbc.query("SELECT * FROM engine_feed_delivery WHERE operation_id=?",(rs,row)->{
            if(!subject.equals(rs.getString("subject_key"))) throw new NaturalReceipt.AccessDenied();
            return map(rs);
        },id).stream().findFirst();
    }
    /** 领取单调执行权，远程调用在此短事务提交之后进行。 */
    public Optional<Operation> acquire(UUID id,String subject) {
        return transactions.execute(tx->jdbc.query("""
                UPDATE engine_feed_delivery SET fence=fence+1,lease_until=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp()
                WHERE operation_id=? AND subject_key=? AND state='PREPARING'
                    AND (lease_until IS NULL OR lease_until<=clock_timestamp()) RETURNING *
                """,(rs,row)->map(rs),id,subject).stream().findFirst());
    }
    /** 资格仅采用数据库毫秒时钟，应用时区不影响到期边界。 */
    public Instant databaseNow() {
        return transactions.execute(tx->jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant().truncatedTo(ChronoUnit.MILLIS));
    }
    /** 首次完整冻结前禁止准备任何广告；纯自然计划可直接完成。 */
    public boolean freeze(Operation operation,Plan plan) {
        if(plan.entries().stream().anyMatch(e->e.preparation()!=null)) throw new IllegalArgumentException("初始 feed 计划不能含准备结果");
        validateSize(operation,plan);
        return Boolean.TRUE.equals(transactions.execute(tx->jdbc.update("""
                UPDATE engine_feed_delivery SET plan_json=?,state=?,updated_at=clock_timestamp(),
                    lease_until=CASE WHEN ? THEN NULL ELSE clock_timestamp()+interval '30 seconds' END
                WHERE operation_id=? AND subject_key=? AND fence=? AND state='PREPARING' AND plan_json IS NULL AND lease_until>clock_timestamp()
                """,write(plan),plan.complete()?State.COMPLETE.name():State.PREPARING.name(),plan.complete(),
                operation.request().operationId(),operation.request().subject(),operation.fence())==1));
    }
    /** 只补齐原广告的首次结果，同时检查旧快照与 fence，避免并发覆盖。 */
    public boolean progress(Operation operation,Plan plan) {
        if(operation.plan()==null || !operation.plan().bucket().equals(plan.bucket())
                || !Objects.equals(operation.plan().traceId(),plan.traceId()) || operation.plan().adDisposition()!=plan.adDisposition()
                || operation.plan().entries().size()!=plan.entries().size()) throw new IllegalArgumentException("feed 冻结上下文不可变");
        for(int i=0;i<plan.entries().size();i++) {
            var old=operation.plan().entries().get(i); var next=plan.entries().get(i);
            if(old.slot()!=next.slot() || !Objects.equals(old.natural(),next.natural()) || !Objects.equals(old.candidate(),next.candidate())
                    || old.preparation()!=null && !old.preparation().equals(next.preparation())) throw new IllegalArgumentException("feed 条目不可替换");
        }
        validateSize(operation,plan);
        return Boolean.TRUE.equals(transactions.execute(tx->jdbc.update("""
                UPDATE engine_feed_delivery SET plan_json=?,state=?,updated_at=clock_timestamp(),
                    lease_until=CASE WHEN ? THEN NULL ELSE clock_timestamp()+interval '30 seconds' END
                WHERE operation_id=? AND subject_key=? AND fence=? AND state='PREPARING' AND plan_json=? AND lease_until>clock_timestamp()
                """,write(plan),plan.complete()?State.COMPLETE.name():State.PREPARING.name(),plan.complete(),
                operation.request().operationId(),operation.request().subject(),operation.fence(),write(operation.plan()))==1));
    }
    /** 释放自己的领取，不能影响接管操作的新执行器。 */
    public void relinquish(UUID id,long fence) {
        transactions.executeWithoutResult(tx->jdbc.update("UPDATE engine_feed_delivery SET lease_until=NULL WHERE operation_id=? AND fence=? AND state='PREPARING'",id,fence));
    }
    private void validateSize(Operation operation,Plan plan) {
        if(plan.entries().size()>operation.request().size() || write(plan).getBytes(StandardCharsets.UTF_8).length>FeedDelivery.MAX_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException("feed 快照超过边界");
        }
    }
    private Operation map(ResultSet rs) throws SQLException {
        try {
            String plan=rs.getString("plan_json");
            return new Operation(json.readValue(rs.getString("request_json"),Request.class),json.readValue(rs.getString("settings_json"),Settings.class),
                    State.valueOf(rs.getString("state")),rs.getLong("fence"),plan==null?null:json.readValue(plan,Plan.class));
        } catch(java.io.IOException ex) { throw new IllegalStateException("feed 持久化版本不兼容"); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("feed 快照编码失败"); }
    }
}
