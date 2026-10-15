package com.recsys.ad.billing;

import com.recsys.ad.persistence.mapper.addb.BillingOutboxMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;
import java.util.Optional;
import java.util.UUID;

/** 每条语句独立原子提交；领取之后不持有数据库锁等待 broker。 */
public class BillingOutboxRepository {
    public static final String MIGRATION = "db/ad-billing/V006__billing_event_delivery.sql";
    public static final int MAX_ATTEMPTS = 20;
    public static final int LEASE_SECONDS = 30;
    /** 仅允许三组服务端固定表族，不接受外部表名或SQL片段。 */
    public enum EventFamily {
        IMPRESSION(6,MIGRATION,"ad.billing"),
        CLICK(10,"db/ad-billing/V010__click_event_delivery.sql","ad.billing.click"),
        CONVERSION(14,"db/ad-billing/V014__conversion_event_delivery.sql","ad.billing.conversion");
        private final int version;
        private final String migration;
        private final String metrics;
        EventFamily(int version,String migration,String metrics) {
            this.version=version;this.migration=migration;this.metrics=metrics;
        }
        /** 指标按有限业务族分开，不添加事件或用户ID标签。 */
        public String metrics() { return metrics; }
    }
    private final BillingOutboxMapper mapper;
    private final EventFamily family;
    /** 默认CPM表族，仍通过独立短语句提交领取。 */
    public BillingOutboxRepository(SqlSessionTemplate sessions) { this(sessions,EventFamily.IMPRESSION); }
    /** 点击与CPA复用可靠投递算法，XML固定表族并逐语句保留5秒查询超时。 */
    public BillingOutboxRepository(SqlSessionTemplate sessions,EventFamily family) {
        this.family=java.util.Objects.requireNonNull(family);
        this.mapper=sessions.getMapper(BillingOutboxMapper.class);
    }
    public record Pending(UUID eventId, String destination, String key, String payload, long fence, int attempts) {}
    public record Backlog(long pending, long dead, double oldestSeconds) {}

    /** 只校验迁移，不能在应用启动时隐式改共享数据库。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(family.migration).equals(
                DataAccessUtils.requiredSingleResult(mapper.migrationHashes(family.version)))) {
            throw new IllegalStateException("adDb 事件投递迁移缺失或漂移");
        }
        mapper.verifyOutbox(family);mapper.verifyFact(family);mapper.verifyDaily(family);
    }

    /** 崩溃也耗用一次领取；有界扫出耗尽租约，避免最后一次崩溃永久卡在 SENDING。 */
    public int exhaust() {
        return mapper.exhaust(family,MAX_ATTEMPTS);
    }

    /** 原子领取单条；多个进程不会同时拿到同一 fence。 */
    public Optional<Pending> claim() {
        return mapper.claim(family,MAX_ATTEMPTS,LEASE_SECONDS).stream().findFirst()
                .map(row->new Pending(row.eventId(),row.destination(),row.key(),row.payload(),row.fence(),row.attempts()));
    }

    /** broker 确认只证明送达；必须仍持有效租约才标记，否则交给新执行器重发。 */
    public boolean published(Pending event) {
        return mapper.published(family,event.eventId(),event.fence())==1;
    }

    /** 不保存异常消息/堆栈，退避和最大次数持久化，重启不会清零。 */
    public boolean failed(Pending event, long delayMs, String errorType) {
        if (delayMs<0 || delayMs>60000 || errorType==null || !errorType.matches("[A-Za-z0-9_]{1,80}")) {
            throw new IllegalArgumentException("无效重试参数");
        }
        return mapper.failed(family,MAX_ATTEMPTS,delayMs,errorType,event.eventId(),event.fence())==1;
    }

    /** 受控恢复工具使用：仅重开 DEAD，保留原事件并同语句写审计；重复操作无新效果。 */
    public boolean replay(UUID replayId, UUID eventId, String actor, String reason) {
        if (replayId==null || eventId==null || actor==null || actor.isBlank() || actor.length()>200
                || reason==null || reason.isBlank() || reason.length()>500) throw new IllegalArgumentException("重放须有操作者与原因");
        return mapper.replay(family,replayId,eventId,actor,reason)==1;
    }

    /** 仅在启用 relay 的低频 tick 观测；无高基数标签，运维据此定位积压。 */
    public Backlog backlog() {
        var row=DataAccessUtils.requiredSingleResult(mapper.backlog(family));
        return new Backlog(row.pending(),row.dead(),row.oldestSeconds());
    }
}
