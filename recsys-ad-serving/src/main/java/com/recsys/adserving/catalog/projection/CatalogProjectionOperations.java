package com.recsys.adserving.catalog.projection;

import java.util.UUID;
import static com.recsys.adserving.catalog.projection.CatalogProjectionProtocol.*;

/** 内部维护入口：独立恢复开关与显式审计，不从公网接收 actor 或任意 broker。 */
public class CatalogProjectionOperations {
    private final CatalogProjectionStore store;
    private final CatalogKafkaProjection kafka;
    private final boolean enabled;
    /** 由已验证的配置装配，关闭消费不妨碍获授权的恢复操作。 */
    public CatalogProjectionOperations(CatalogProjectionStore store, CatalogKafkaProjection kafka, boolean enabled) {
        this.store = store; this.kafka = kafka; this.enabled = enabled;
    }
    /** 初次登记只使用当前已绑定的环境，原 baseline 说明持久审计。 */
    public void initialize(UUID command, UUID source, String baseline, String actor, String reason) {
        requireEnabled(); kafka.initialize(command, source, baseline, actor, reason);
    }
    /** 有审计的新恢复轮；完成后由受控进程重启消费。 */
    public void resume(UUID command, String actor, String reason) { requireEnabled(); store.resume(command, actor, reason); }
    /** 指定任务重试不清除其他广告的失败历史。 */
    public long retryTask(UUID command, long task, String actor, String reason) { requireEnabled(); return store.retryTask(command, task, actor, reason); }
    /** 指定广告恢复只追加任务，不绕过未完成前驱。 */
    public long refresh(UUID command, long ad, String actor, String reason) { requireEnabled(); return store.refresh(command, ad, actor, reason); }
    /** 修复协议支持后重检原隔离投递，不接受替换内容，不回退 Kafka 位点。 */
    public void recheck(UUID command, int partition, long offset, String actor, String reason) {
        requireEnabled(); store.recheck(command, partition, offset, actor, reason);
    }
    private void requireEnabled() { if (!enabled) throw new Failure(Code.NOT_ELIGIBLE); }
}
