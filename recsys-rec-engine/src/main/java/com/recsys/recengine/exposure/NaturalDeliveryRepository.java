package com.recsys.recengine.exposure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.recsys.common.exposure.*;
import com.recsys.recengine.exposure.NaturalDelivery.*;
import org.springframework.core.io.ClassPathResource;
import com.recsys.recengine.persistence.mapper.primary.NaturalDeliveryMapper;
import com.recsys.recengine.persistence.mapper.primary.NaturalDeliveryMapper.OperationRow;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** engine primary PG 负责自然整次结果；短事务租约与单条冻结，不跨 owner 持锁。 */
public class NaturalDeliveryRepository {
    public static final String MIGRATION = "db/engine-delivery/V002__natural_delivery.sql";
    private final NaturalDeliveryMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    /** 从实际会话工厂取事务资源，保持独立短事务与SQL使用同一DataSource。 */
    public NaturalDeliveryRepository(SqlSessionTemplate sessions, ObjectMapper json) {
        this.mapper = sessions.getMapper(NaturalDeliveryMapper.class);
        var dataSource = Objects.requireNonNull(sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource());
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); transactions.setTimeout(5);
    }
    /** 启动只核对 owner 的 V002，不修改已执行 V001。 */
    public void verifySchema() {
        if (!migrationSha256().equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes()))) {
            throw new IllegalStateException("engine 自然交付迁移缺失或摘要漂移");
        }
        mapper.verifyOperation();
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
            mapper.create(request.operationId(),request.subject(),write(request),write(policy));
            var stored = read(request.operationId(),request.subject()).orElseThrow();
            if (!request.equals(stored.request())) throw new NaturalReceipt.Conflict();
            return stored;
        });
    }
    /** 无写入查询，不因请求到达而重启计算或续期。 */
    public Optional<Operation> find(UUID id, String subject) { return transactions.execute(tx -> read(id,subject)); }
    private Optional<Operation> read(UUID id, String subject) {
        NaturalReceipt.validateSubject(subject);
        return mapper.find(id).stream().map(row -> {
            if (!subject.equals(row.subjectKey())) throw new NaturalReceipt.AccessDenied();
            return map(row);
        }).findFirst();
    }
    /** 领取独立提交后才允许候选和内容远程调用；失效 fence 无权冻结。 */
    public Optional<Operation> acquire(UUID id, String subject) {
        return transactions.execute(tx -> mapper.acquire(id,subject).stream().map(this::map).findFirst());
    }
    /** 使用数据库时间签发，编码仅保留毫秒，不取应用服务器时钟。 */
    public Instant databaseNow() {
        return transactions.execute(tx -> mapper.databaseNow().toInstant().truncatedTo(ChronoUnit.MILLIS));
    }
    /** 整次响应原子冻结；超时/提交未知由原 operationId 查询，不交出未提交资格。 */
    public boolean freeze(Operation op, View result) {
        if (result.state()!=State.COMPLETE || !result.operationId().equals(op.request().operationId().toString()) || result.items().size()>op.request().size()) {
            throw new IllegalArgumentException("自然交付冻结结果不匹配");
        }
        String encoded=write(result);
        if(encoded.getBytes(StandardCharsets.UTF_8).length>4194304) throw new IllegalArgumentException("自然交付快照超限");
        return Boolean.TRUE.equals(transactions.execute(tx -> mapper.freeze(
                op.request().operationId(),op.request().subject(),op.fence(),encoded)==1));
    }
    /** 失败只释放自己的执行权；不能清除新执行器的租约。 */
    public void relinquish(UUID id,long fence) {
        transactions.executeWithoutResult(tx -> mapper.relinquish(id,fence));
    }
    private Operation map(OperationRow row) {
        try {
            String result=row.resultJson();
            return new Operation(json.readValue(row.requestJson(),Request.class),json.readValue(row.policyJson(),NaturalQualification.Policy.class),
                    State.valueOf(row.state()),row.fence(),result==null?null:json.readValue(result,View.class));
        } catch(java.io.IOException ex) { throw new IllegalStateException("自然交付持久化版本不兼容"); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("自然交付快照编码失败"); }
    }
}
