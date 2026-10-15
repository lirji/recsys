package com.recsys.recengine.delivery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.CpmPrice;
import com.recsys.common.ad.billing.Reservation;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import com.recsys.recengine.delivery.DeliveryOperation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** engine primary 数据库唯一写入入口；每个进度更新为带 fence 的单语句短事务。 */
public class DeliveryOperationRepository {
    public static final String MIGRATION = "db/engine-delivery/V001__delivery_operation.sql";
    public static final String CLICK_MIGRATION="db/engine-delivery/V004__click_delivery.sql";
    private final ChargeMode mode;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public DeliveryOperationRepository(JdbcTemplate jdbc,ObjectMapper json) { this(jdbc,json,ChargeMode.IMPRESSION); }
    /** 服务端固定模式隔离两种交付检查点，复用租约和原请求恢复算法。 */
    public DeliveryOperationRepository(JdbcTemplate jdbc,ObjectMapper json,ChargeMode mode) {
        this.jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(5);
        this.json=json.copy();this.mode=java.util.Objects.requireNonNull(mode);
    }
    /** 固定模式供owner装配及路由核验，运行中不变化。 */
    public ChargeMode mode() { return mode; }

    /** 校验 engine 自有历史，绝不在 adDb 上保存整次操作。 */
    public void verifySchema() {
        if (!migrationSha256(mode).equals(jdbc.queryForObject("SELECT script_sha256 FROM engine_delivery_schema_history WHERE version = ?", String.class,mode==ChargeMode.IMPRESSION?1:4))) {
            throw new IllegalStateException("engine 交付迁移缺失或摘要漂移");
        }
        jdbc.queryForList(sql("SELECT operation_id, fence, plan_json FROM engine_ad_delivery LIMIT 0"));
    }
    /** 启动与显式迁移使用同一包内脚本摘要，禁止改写已执行历史。 */
    public static String migrationSha256() { return migrationSha256(ChargeMode.IMPRESSION); }
    /** 不改变旧版本摘要，新模式只核验自己的追加迁移。 */
    public static String migrationSha256(ChargeMode mode) {
        try (var stream = new ClassPathResource(mode==ChargeMode.IMPRESSION?MIGRATION:CLICK_MIGRATION).getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("无法校验 engine 迁移", ex); }
    }

    /** 同 ID 固定请求与首次实验参数；后来配置变化不改变原操作。 */
    public DeliveryOperation create(Request request, String bucket, double reserve) {
        CpmPrice.decimal(reserve);
        jdbc.update(sql("""
                INSERT INTO engine_ad_delivery(operation_id, subject_key, request_json, bucket, reserve)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """), request.operationId(), request.subject(), write(request), bucket, reserve);
        var stored = find(request.operationId(), request.subject()).orElseThrow();
        if (!request.equals(stored.request())) throw new SandboxDelivery.Conflict();
        return stored;
    }

    /** 读操作不领取租约，也不触发 owner 预占。 */
    public Optional<DeliveryOperation> find(UUID id, String subject) {
        Reservation.validateSubject(subject);
        return jdbc.query(sql("SELECT * FROM engine_ad_delivery WHERE operation_id = ?"), (rs, row) -> {
            if (!subject.equals(rs.getString("subject_key"))) throw new Reservation.AccessDenied();
            return map(rs);
        }, id).stream().findFirst();
    }

    /** 原子租约领取，旧 fence 永远不能覆盖新执行器；数据库时钟统一判定过期。 */
    public Optional<DeliveryOperation> acquire(UUID id, String subject) {
        return jdbc.query(sql("""
                UPDATE engine_ad_delivery SET fence = fence + 1, lease_until = clock_timestamp() + interval '30 seconds',
                    updated_at = clock_timestamp()
                WHERE operation_id = ? AND subject_key = ? AND state = 'PREPARING'
                    AND (lease_until IS NULL OR lease_until <= clock_timestamp()) RETURNING *
                """), (rs, row) -> map(rs), id, subject).stream().findFirst();
    }

    /** 首次计划必须仍持有效租约；落库前禁止发送任何准备请求。 */
    public boolean freeze(DeliveryOperation operation, List<Entry> plan) {
        if (plan.size() > operation.request().slots()) throw new IllegalArgumentException("计划超过请求位数");
        for (int i = 0; i < plan.size(); i++) {
            if (plan.get(i).slot() != i + 1 || plan.get(i).result() != null) throw new IllegalArgumentException("初始计划顺序非法");
        }
        return jdbc.update(sql("""
                UPDATE engine_ad_delivery SET plan_json = ?, updated_at = clock_timestamp(), lease_until = clock_timestamp() + interval '30 seconds'
                WHERE operation_id = ? AND fence = ? AND lease_until > clock_timestamp() AND plan_json IS NULL AND state = 'PREPARING'
                """), write(plan), operation.request().operationId(), operation.fence()) == 1;
    }

    /** 条目身份与既有结果只增不改；保存条件还校验旧快照，阻止同 fence 的错误覆盖。 */
    public boolean progress(DeliveryOperation operation, List<Entry> plan) {
        if (operation.plan() == null || operation.plan().size() != plan.size()) throw new IllegalArgumentException("冻结计划不可替换");
        for (int i = 0; i < plan.size(); i++) {
            var old = operation.plan().get(i); var next = plan.get(i);
            if (old.slot() != next.slot() || !old.candidate().equals(next.candidate())
                    || (old.result() != null && !old.result().equals(next.result()))) throw new IllegalArgumentException("已冻结条目不可改写");
        }
        boolean complete = plan.stream().allMatch(e -> e.result() != null);
        return jdbc.update(sql("""
                UPDATE engine_ad_delivery SET plan_json = ?, state = ?, updated_at = clock_timestamp(),
                    lease_until = CASE WHEN ? THEN NULL ELSE clock_timestamp() + interval '30 seconds' END
                WHERE operation_id = ? AND fence = ? AND lease_until > clock_timestamp() AND plan_json = ? AND state = 'PREPARING'
                """), write(plan), complete ? "COMPLETE" : "PREPARING", complete,
                operation.request().operationId(), operation.fence(), write(operation.plan())) == 1;
    }

    /** 异常后允许立即恢复；旧执行器不能撤销新 fence 的租约。 */
    public void relinquish(UUID id, long fence) {
        jdbc.update(sql("UPDATE engine_ad_delivery SET lease_until = NULL WHERE operation_id = ? AND fence = ? AND state = 'PREPARING'"), id, fence);
    }

    private DeliveryOperation map(ResultSet rs) throws SQLException {
        try {
            return new DeliveryOperation(json.readValue(rs.getString("request_json"), Request.class), rs.getString("bucket"),
                    rs.getDouble("reserve"), State.valueOf(rs.getString("state")), rs.getLong("fence"),
                    rs.getString("plan_json") == null ? null : List.copyOf(json.readValue(rs.getString("plan_json"), new TypeReference<List<Entry>>() {})));
        } catch (java.io.IOException ex) { throw new IllegalStateException("交付记录版本不兼容"); }
    }
    private String sql(String statement) {
        return mode==ChargeMode.IMPRESSION?statement:statement.replace("engine_ad_delivery","engine_click_delivery");
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("交付记录编码失败"); }
    }
}
