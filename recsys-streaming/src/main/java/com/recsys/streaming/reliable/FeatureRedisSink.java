package com.recsys.streaming.reliable;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Redis是可重建投影；Lua拒绝旧窗口和旧执行器，异常不能被Flink当成功确认。 */
public final class FeatureRedisSink extends RichSinkFunction<String> {
    private final FeatureSettings settings;
    private transient Jedis redis;
    private transient String token;
    private transient String script;
    private transient boolean acquired;

    public FeatureRedisSink(FeatureSettings settings) { this.settings = settings; }
    @Override public void open(Configuration ignored) throws Exception {
        redis = new Jedis(settings.redisHost(), settings.redisPort(), FeatureSettings.IO_TIMEOUT_MS, FeatureSettings.IO_TIMEOUT_MS);
        token = UUID.randomUUID().toString(); acquired = false;
        try (var resource = getClass().getResourceAsStream("/streaming/window-snapshot.lua")) {
            if (resource == null) throw new IllegalStateException(FeatureSettings.INVALID);
            script = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override public void invoke(String snapshot, Context context) throws Exception {
        publish(snapshot);
        getRuntimeContext().getMetricGroup().counter("snapshot_applied").inc();
    }

    /** 心跳重发同一内容也使用原绝对截止时间，不能把旧窗口续成新鲜特征。 */
    public void publish(String snapshot) throws Exception {
        String prefix = settings.namespace();
        if (!acquired) {
            String result = redis.set(prefix + ":lease", token, SetParams.setParams().nx().px(FeatureSettings.LEASE_MS));
            if (!"OK".equals(result)) throw new IllegalStateException(FeatureSettings.CONFLICT);
            acquired = true;
        }
        if (snapshot.getBytes(StandardCharsets.UTF_8).length > FeatureSettings.MAX_BYTES) throw new IllegalArgumentException(FeatureSettings.CAPACITY);
        Object result = redis.eval(script, List.of(prefix + ":control", prefix + ":lease", prefix + ":hot", prefix + ":users"),
                List.of(token, settings.fingerprint(), FeatureSettings.digest(snapshot), snapshot,
                        Long.toString(settings.freshnessMs()), Long.toString(FeatureSettings.LEASE_MS), prefix + ":user:"));
        if (((Number) result).longValue() < 0) throw new IllegalStateException(FeatureSettings.CONFLICT);
    }

    @Override public void close() {
        if (redis == null) return;
        try {
            // 只能释放自身租约；故障退出释放失败由TTL接管，不阻塞Flink故障处理。
            redis.eval("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end return 0",
                    List.of(settings.namespace() + ":lease"), List.of(token));
        } catch (redis.clients.jedis.exceptions.JedisException failure) {
            org.slf4j.LoggerFactory.getLogger(FeatureRedisSink.class).warn("实时特征租约释放失败，将等待租约到期");
        } finally { redis.close(); }
    }
}
