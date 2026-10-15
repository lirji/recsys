package com.recsys.recengine.delivery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.CpmPrice;
import com.recsys.common.ad.billing.Reservation;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import com.recsys.recengine.delivery.DeliveryOperation.*;
import org.springframework.core.io.ClassPathResource;
import com.recsys.recengine.persistence.mapper.primary.DeliveryOperationMapper;
import com.recsys.recengine.persistence.mapper.primary.DeliveryOperationMapper.OperationRow;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** engine primary 数据库唯一写入入口；每个进度更新为带 fence 的单语句短事务。 */
public class DeliveryOperationRepository {
    public static final String MIGRATION = "db/engine-delivery/V001__delivery_operation.sql";
    public static final String CLICK_MIGRATION="db/engine-delivery/V004__click_delivery.sql";
    public static final String CONVERSION_MIGRATION="db/engine-delivery/V005__conversion_delivery.sql";
    private final ChargeMode mode;
    private final DeliveryOperationMapper mapper;
    private final ObjectMapper json;
    /** 默认CPM复用primary会话，使检查点参加原Spring短事务。 */
    public DeliveryOperationRepository(SqlSessionTemplate sessions,ObjectMapper json) { this(sessions,json,ChargeMode.IMPRESSION); }
    /** 服务端固定模式隔离各计费类型交付检查点，复用租约和原请求恢复算法。 */
    public DeliveryOperationRepository(SqlSessionTemplate sessions,ObjectMapper json,ChargeMode mode) {
        this.mapper=sessions.getMapper(DeliveryOperationMapper.class);
        this.json=json.copy();this.mode=java.util.Objects.requireNonNull(mode);
    }
    /** 固定模式供owner装配及路由核验，运行中不变化。 */
    public ChargeMode mode() { return mode; }

    /** 校验 engine 自有历史，绝不在 adDb 上保存整次操作。 */
    public void verifySchema() {
        if (!migrationSha256(mode).equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes(switch(mode){case IMPRESSION->1;case CLICK->4;case CONVERSION->5;})))) {
            throw new IllegalStateException("engine 交付迁移缺失或摘要漂移");
        }
        mapper.verifyOperation(mode);
    }
    /** 启动与显式迁移使用同一包内脚本摘要，禁止改写已执行历史。 */
    public static String migrationSha256() { return migrationSha256(ChargeMode.IMPRESSION); }
    /** 不改变旧版本摘要，新模式只核验自己的追加迁移。 */
    public static String migrationSha256(ChargeMode mode) {
        try (var stream = new ClassPathResource(switch(mode){case IMPRESSION->MIGRATION;case CLICK->CLICK_MIGRATION;case CONVERSION->CONVERSION_MIGRATION;}).getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("无法校验 engine 迁移", ex); }
    }

    /** 同 ID 固定请求与首次实验参数；后来配置变化不改变原操作。 */
    public DeliveryOperation create(Request request, String bucket, double reserve) {
        CpmPrice.decimal(reserve);
        mapper.create(mode, request.operationId(), request.subject(), write(request), bucket, reserve);
        var stored = find(request.operationId(), request.subject()).orElseThrow();
        if (!request.equals(stored.request())) throw new SandboxDelivery.Conflict();
        return stored;
    }

    /** 读操作不领取租约，也不触发 owner 预占。 */
    public Optional<DeliveryOperation> find(UUID id, String subject) {
        Reservation.validateSubject(subject);
        return mapper.find(mode, id).stream().map(row -> {
            if (!subject.equals(row.subjectKey())) throw new Reservation.AccessDenied();
            return map(row);
        }).findFirst();
    }

    /** 原子租约领取，旧 fence 永远不能覆盖新执行器；数据库时钟统一判定过期。 */
    public Optional<DeliveryOperation> acquire(UUID id, String subject) {
        return mapper.acquire(mode, id, subject).stream().map(this::map).findFirst();
    }

    /** 首次计划必须仍持有效租约；落库前禁止发送任何准备请求。 */
    public boolean freeze(DeliveryOperation operation, List<Entry> plan) {
        if (plan.size() > operation.request().slots()) throw new IllegalArgumentException("计划超过请求位数");
        for (int i = 0; i < plan.size(); i++) {
            if (plan.get(i).slot() != i + 1 || plan.get(i).result() != null) throw new IllegalArgumentException("初始计划顺序非法");
        }
        return mapper.freeze(mode, operation.request().operationId(), operation.fence(), write(plan)) == 1;
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
        return mapper.progress(mode, operation.request().operationId(), operation.fence(), write(plan),
                write(operation.plan()), complete ? State.COMPLETE.name() : State.PREPARING.name(), complete) == 1;
    }

    /** 异常后允许立即恢复；旧执行器不能撤销新 fence 的租约。 */
    public void relinquish(UUID id, long fence) {
        mapper.relinquish(mode, id, fence);
    }

    private DeliveryOperation map(OperationRow row) {
        try {
            return new DeliveryOperation(json.readValue(row.requestJson(), Request.class), row.bucket(),
                    row.reserve(), State.valueOf(row.state()), row.fence(),
                    row.planJson() == null ? null : List.copyOf(json.readValue(row.planJson(), new TypeReference<List<Entry>>() {})));
        } catch (java.io.IOException ex) { throw new IllegalStateException("交付记录版本不兼容"); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("交付记录编码失败"); }
    }
}
