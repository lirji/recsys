package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.Reservation;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import com.recsys.ad.persistence.mapper.addb.PreparationMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** ad owner 准备检查点；独立原子语句，不跨报价/预占假装一个事务。 */
public class PreparationRepository {
    public static final String MIGRATION = "db/ad-billing/V004__delivery_preparation.sql";
    public static final String CLICK_MIGRATION = "db/ad-billing/V011__click_preparation.sql";
    public static final String CONVERSION_MIGRATION = "db/ad-billing/V015__conversion_preparation.sql";
    private final ChargeMode mode;
    private final PreparationMapper mapper;
    private final DataSource dataSource;
    private final ObjectMapper json;
    /** CPM默认模式沿用原入口，实际数据源来自会话工厂。 */
    public PreparationRepository(SqlSessionTemplate sessions, ObjectMapper json) { this(sessions,json,ChargeMode.IMPRESSION); }
    /** 同一恢复算法按有限模式使用独立表；不允许外部SQL标识符。 */
    public PreparationRepository(SqlSessionTemplate sessions,ObjectMapper json,ChargeMode mode) {
        this.mapper=sessions.getMapper(PreparationMapper.class);
        this.dataSource=sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
        this.json=json.copy();this.mode=java.util.Objects.requireNonNull(mode);
    }
    /** 固定模式供owner装配及路由核验，运行中不变化。 */
    public ChargeMode mode() { return mode; }
    /** 连接池身份用于阻止不同 owner 资源混用。 */
    public javax.sql.DataSource dataSource() { return dataSource; }

    /** 版本历史只校验，不在应用启动时迁移库。 */
    public void verifySchema() {
        int version = switch(mode) { case IMPRESSION -> 4; case CLICK -> 11; case CONVERSION -> 15; };
        String migration = switch(mode) { case IMPRESSION -> MIGRATION; case CLICK -> CLICK_MIGRATION; case CONVERSION -> CONVERSION_MIGRATION; };
        if (!BudgetRepository.migrationSha256(migration).equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes(version)))) {
            throw new IllegalStateException("adDb 准备迁移缺失或摘要漂移");
        }
        mapper.verifyPreparation(mode);
    }

    /** 时钟只取 owner 数据库，避免实例时钟差改变期限边界。 */
    public Instant now() { return mapper.now().toInstant(); }

    /** 先保存不可变请求，进程在任一后续步骤失败都能同 ID 恢复。 */
    public void claim(Prepare request) {
        mapper.claim(mode, new PreparationMapper.Claim(request.childId(), request.operationId(), request.subject(), request.slot(), write(request)));
        var rows = mapper.requests(mode, request.childId());
        if (rows.size() != 1 || !request.equals(read(rows.getFirst(), Prepare.class))) throw new Conflict();
    }

    /** 认证后查询；未完成和未观察到均返回空，调用方只可继续使用原 ID。 */
    public Optional<Prepared> find(UUID id, String subject) {
        Reservation.validateSubject(subject);
        return mapper.find(mode, id).stream().findFirst().flatMap(row -> {
            if (!subject.equals(row.subject())) throw new Reservation.AccessDenied();
            return row.resultJson() == null ? Optional.empty() : Optional.of(read(row.resultJson(), Prepared.class));
        });
    }

    /** 首个结果获胜，密钥轮换或并发执行也返回数据库里同一 token。 */
    public Prepared complete(Prepared result, String subject) {
        mapper.complete(mode, result.childId(), write(result));
        return find(result.childId(), subject).orElseThrow(() -> new IllegalStateException("准备结果不可查询"));
    }

    /** 已完成原结果先恢复；原命令必须完全一致，不能借密钥轮换改请求。 */
    public Optional<Prepared> original(Prepare request) {
        return mapper.original(mode, request.childId()).stream().findFirst().flatMap(row -> {
            if (!request.subject().equals(row.subject())) throw new Reservation.AccessDenied();
            if (!request.equals(read(row.requestJson(), Prepare.class))) throw new Conflict();
            return row.resultJson() == null ? Optional.empty() : Optional.of(read(row.resultJson(), Prepared.class));
        });
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("准备记录编码失败"); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("准备记录格式不兼容"); }
    }
}
