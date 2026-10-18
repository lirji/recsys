package com.recsys.adserving.catalog.projection;

import com.recsys.common.ad.BidwordInvCodec;
import com.recsys.common.ad.CatalogEventCodec;
import com.recsys.common.ad.VersionedAdCatalogEvent;
import com.recsys.common.constant.RedisKeys;
import com.recsys.adserving.persistence.mapper.addb.CatalogProjectionMapper.Task;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.*;
import static com.recsys.adserving.catalog.projection.CatalogProjectionProtocol.*;

/** 一个任务一个原子 Lua；旧关键词来自持久快照，不扫描整个关键词集合。 */
public class CatalogRedisProjection {
    public static final String VERSION_PREFIX = "catalog:projection:version:";
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<String> SCAN_PAGE = new DefaultRedisScript<>(
            "local r=redis.call('SCAN',ARGV[1],'MATCH',ARGV[2],'COUNT',100); if #r[2]>0 then return '-1' end; return r[1]", String.class);
    private static final int MAX_SCAN_PAGES = 100;
    static { SCRIPT.setLocation(new ClassPathResource("catalog-projection/refresh.lua")); SCRIPT.setResultType(Long.class); }
    private final CatalogProjectionStore store;
    private final StringRedisTemplate redis;

    /** 调用方必须提供已核验超时的 ad-serving 独占 Redis 连接。 */
    public CatalogRedisProjection(CatalogProjectionStore store, StringRedisTemplate redis) { this.store = store; this.redis = redis; }

    /** 初次登记只检查命名空间为空，不删除任何现有数据。 */
    public void requireEmpty() {
        for (String pattern : List.of("bidword:inv:*", VERSION_PREFIX + "*")) {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
            String cursor = "0";
            for (int page = 0; page < MAX_SCAN_PAGES; page++) {
                if (System.nanoTime() > deadline) throw new Failure(Code.DEPENDENCY_FAILURE);
                cursor = redis.execute(SCAN_PAGE, List.of(), cursor, pattern);
                if ("-1".equals(cursor)) throw new Failure(Code.TARGET_NOT_EMPTY);
                if ("0".equals(cursor)) break;
                if (cursor == null || page == MAX_SCAN_PAGES - 1) throw new Failure(Code.DEPENDENCY_FAILURE);
            }
        }
    }

    /** 每次只推进一个任务，持久失败不会被定时器当作成功。 */
    public boolean advance() {
        Task task = store.claimTask();
        if (task == null) return false;
        try { apply(task); afterRedis(task); store.finish(task); }
        catch (RuntimeException failure) {
            store.fail(task, failure instanceof Failure classified ? classified.code() : Code.DEPENDENCY_FAILURE);
        }
        return true;
    }

    /** 暴露协议动作供故障验收：故意不确认时必须能重放原脚本。 */
    public void apply(Task task) {
        VersionedAdCatalogEvent next = store.decode(task.payload());
        CatalogEventCodec.validate(next);
        VersionedAdCatalogEvent old = task.oldPayload() == null ? null : store.decode(task.oldPayload());
        if (old != null) CatalogEventCodec.validate(old);
        if (next.adId() != task.adId() || next.aggregateVersion() != task.aggregateVersion()
                || old != null && (old.adId() != next.adId() || !old.sourceId().equals(next.sourceId()))) throw new Failure(Code.INVALID_EVENT);
        List<Member> removed = members(old), added = members(next);
        List<String> keys = new ArrayList<>();
        keys.add(VERSION_PREFIX + next.sourceId() + ":" + task.adId());
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (Member member : java.util.stream.Stream.concat(removed.stream(), added.stream()).toList()) {
            if (!indexes.containsKey(member.key())) { keys.add(member.key()); indexes.put(member.key(), keys.size()); }
        }
        List<String> args = new ArrayList<>();
        args.add(String.format(Locale.ROOT, "%019d", task.taskId()));
        args.add(String.format(Locale.ROOT, "%019d", task.aggregateVersion()));
        args.add(Integer.toString(removed.size()));
        for (Member member : removed) { args.add(indexes.get(member.key()).toString()); args.add(member.value()); }
        args.add(Integer.toString(added.size()));
        for (Member member : added) { args.add(indexes.get(member.key()).toString()); args.add(member.value()); args.add(Double.toString(member.score())); }
        if (!Long.valueOf(1).equals(redis.execute(SCRIPT, keys, args.toArray()))) throw new Failure(Code.DEPENDENCY_FAILURE);
    }

    /** 测试在真实 Redis 成功之后强退，产品默认没有故障注入开关。 */
    protected void afterRedis(Task task) { }
    private static List<Member> members(VersionedAdCatalogEvent event) {
        if (event == null || !event.servable()) return List.of();
        return event.bidwords().stream().map(word -> new Member(RedisKeys.bidwordInv(word.keyword()),
                BidwordInvCodec.encode(event.adId(), word.id(), event.ad().itemId(), event.advertiserId(), event.ad().qualityScore()), word.bid())).toList();
    }
    private record Member(String key, String value, double score) { }
}
