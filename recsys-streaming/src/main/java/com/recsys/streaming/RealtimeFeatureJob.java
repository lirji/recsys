package com.recsys.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.constant.RedisKeys;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.SlidingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 实时特征流作业(Flink,本地 MiniCluster 嵌入式运行)。
 *
 * <p>消费 Kafka {@code behavior-events}(由 recsys-behavior 在 {@code use-kafka=true} 时投递,JSON =
 * {@link com.recsys.common.dto.BehaviorEvent}),近实时算两类特征写 Redis,与离线 T+1 作业互补:
 * <ol>
 *   <li><b>实时热度</b>:正反馈按权重在滑动窗口内累加 → ZSet {@link RedisKeys#RT_HOT_RECALL}。
 *       在线 {@code HotRecaller} 优先读它,缺失回落离线 {@code recall:hot} —— 直接体现"实时热度生效"。</li>
 *   <li><b>用户实时类目偏好</b>:按 item 类目富化后,每用户滑动窗口内各类目计数 →
 *       Hash {@link RedisKeys#rtUser(long)}(field=category)。供画像/标签召回叠加"用户近期在看哪类"。</li>
 * </ol>
 *
 * <p>窗口用<strong>处理时间</strong>滑动窗口(本地演示无需水位线);Redis 键带 TTL,过期自然淘汰。
 *
 * <p>运行:先 {@code docker compose --profile full up -d}(Kafka),behavior 以 {@code BEHAVIOR_USE_KAFKA=true} 起,
 * 再 {@code bash run-streaming.sh}(fat jar + Java 21 所需 --add-opens)。
 *
 * <p>参数(均可用环境变量,见 {@code arg()}):--kafka(localhost:9092)、--topic(behavior-events)、
 * --redis-host(localhost)、--redis-port(6379)、--pg-url(jdbc:postgresql://localhost:5432/recsys)、
 * --window-min(滑动窗口大小,默认 10)、--slide-sec(滑动步长,默认 20)、--ttl-sec(Redis TTL,默认 7200)。
 */
public class RealtimeFeatureJob {

    private static final Logger log = LoggerFactory.getLogger(RealtimeFeatureJob.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);
        String kafka = arg(a, "kafka", "KAFKA_SERVERS", "localhost:9092");
        String topic = arg(a, "topic", "BEHAVIOR_TOPIC", "behavior-events");
        String redisHost = arg(a, "redis-host", "REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(arg(a, "redis-port", "REDIS_PORT", "6379"));
        String pgUrl = arg(a, "pg-url", "PG_URL", "jdbc:postgresql://localhost:5432/recsys");
        String pgUser = arg(a, "pg-user", "PG_USER", "recsys");
        String pgPass = arg(a, "pg-pass", "PG_PASSWORD", "recsys");
        int windowMin = Integer.parseInt(arg(a, "window-min", "RT_WINDOW_MIN", "10"));
        int slideSec = Integer.parseInt(arg(a, "slide-sec", "RT_SLIDE_SEC", "20"));
        int ttlSec = Integer.parseInt(arg(a, "ttl-sec", "RT_TTL_SEC", "7200"));

        log.info("实时特征作业启动:kafka={}, topic={}, redis={}:{}, 窗口={}min/滑动{}s, ttl={}s",
                kafka, topic, redisHost, redisPort, windowMin, slideSec, ttlSec);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);   // 本地演示单并行度,Redis sink 简单

        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(kafka)
                .setTopics(topic)
                .setGroupId("recsys-streaming")
                .setStartingOffsets(OffsetsInitializer.latest())   // 只处理作业启动后的新事件
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        // 原始 JSON → Ev(POJO),解析失败丢弃;并富化 item 类目(从 PG 一次性加载)
        SingleOutputStreamOperator<Ev> events = env
                .fromSource(source, WatermarkStrategy.noWatermarks(), "behavior-kafka")
                .map(new CategoryEnricher(pgUrl, pgUser, pgPass))
                .name("parse+enrich")
                .filter(e -> e != null && e.isPositive());

        var window = SlidingProcessingTimeWindows.of(
                Duration.ofMinutes(windowMin), Duration.ofSeconds(slideSec));

        // 管线 1:实时热度 ZSet recall:rt_hot
        events.map(e -> Tuple2.of(e.itemId, e.weight()))
                .returns(org.apache.flink.api.common.typeinfo.Types.TUPLE(
                        org.apache.flink.api.common.typeinfo.Types.LONG,
                        org.apache.flink.api.common.typeinfo.Types.DOUBLE))
                .keyBy(t -> t.f0)
                .window(window)
                .sum(1)
                .addSink(new RedisHotSink(redisHost, redisPort, ttlSec))
                .name("rt-hot → redis");

        // 管线 2:用户实时类目偏好 Hash rt:user:{userId}
        events.filter(e -> e.category != null && !e.category.isEmpty())
                .keyBy(e -> e.userId)
                .window(window)
                .process(new UserCategoryCount())
                .addSink(new RedisUserCatSink(redisHost, redisPort, ttlSec))
                .name("rt-user-cat → redis");

        env.execute("recsys-realtime-features");
    }

    // ---------- 解析 + 类目富化 ----------

    /** 一条行为事件(Flink POJO:public 字段 + 无参构造,走高效 POJO 序列化)。 */
    public static class Ev {
        public long userId;
        public long itemId;
        public String action;
        public double value;
        public String category;

        public boolean isPositive() {
            return action != null && switch (action) {
                case "CLICK", "LIKE", "PLAY", "RATING" -> true;
                default -> false;   // 排除 IMPRESSION
            };
        }

        /** 正反馈权重(与 UserEmbeddingJob 口径一致)。 */
        public double weight() {
            return switch (action) {
                case "RATING" -> value > 0 ? value : 1.0;
                case "LIKE" -> 2.0;
                default -> 1.0;   // CLICK / PLAY
            };
        }
    }

    /** 解析 JSON → Ev,并用 open() 时一次性加载的 item→category 映射富化类目。 */
    public static class CategoryEnricher extends RichMapFunction<String, Ev> {
        private final String url;
        private final String user;
        private final String pass;
        private transient Map<Long, String> catMap;

        public CategoryEnricher(String url, String user, String pass) {
            this.url = url;
            this.user = user;
            this.pass = pass;
        }

        @Override
        public void open(Configuration parameters) {
            catMap = new HashMap<>();
            try (Connection c = DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT item_id, category FROM item")) {
                while (rs.next()) {
                    catMap.put(rs.getLong(1), rs.getString(2));
                }
                log.info("类目富化:加载 item→category {} 条", catMap.size());
            } catch (Exception e) {
                log.warn("加载 item 类目失败(类目偏好管线将无类目):{}", e.getMessage());
            }
        }

        @Override
        public Ev map(String json) {
            try {
                JsonNode n = MAPPER.readTree(json);
                Ev e = new Ev();
                e.userId = n.path("userId").asLong();
                e.itemId = n.path("itemId").asLong();
                e.action = n.path("action").asText(null);
                e.value = n.path("value").asDouble(0);
                e.category = catMap == null ? null : catMap.get(e.itemId);
                return e;
            } catch (Exception ex) {
                log.debug("解析行为 JSON 失败,丢弃:{}", ex.getMessage());
                return null;   // 由后续 filter 丢弃
            }
        }
    }

    /** 每用户窗口内各类目计数,逐 (user,category,count) 发出。 */
    public static class UserCategoryCount
            extends ProcessWindowFunction<Ev, Tuple3<Long, String, Long>, Long, TimeWindow> {
        @Override
        public void process(Long userId, Context ctx, Iterable<Ev> elements,
                            Collector<Tuple3<Long, String, Long>> out) {
            Map<String, Long> counts = new HashMap<>();
            for (Ev e : elements) {
                counts.merge(e.category, 1L, Long::sum);
            }
            for (var entry : counts.entrySet()) {
                out.collect(Tuple3.of(userId, entry.getKey(), entry.getValue()));
            }
        }
    }

    // ---------- Redis sinks ----------

    /** 实时热度 ZSet:ZADD recall:rt_hot <score> <itemId> + TTL。 */
    public static class RedisHotSink extends RichSinkFunction<Tuple2<Long, Double>> {
        private final String host;
        private final int port;
        private final int ttl;
        private transient Jedis jedis;

        public RedisHotSink(String host, int port, int ttl) {
            this.host = host;
            this.port = port;
            this.ttl = ttl;
        }

        @Override
        public void open(Configuration parameters) {
            jedis = new Jedis(host, port);
        }

        @Override
        public void invoke(Tuple2<Long, Double> v, Context context) {
            jedis.zadd(RedisKeys.RT_HOT_RECALL, v.f1, String.valueOf(v.f0));
            jedis.expire(RedisKeys.RT_HOT_RECALL, ttl);
        }

        @Override
        public void close() {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /** 用户实时类目偏好 Hash:HSET rt:user:{u} <category> <count> + TTL。 */
    public static class RedisUserCatSink extends RichSinkFunction<Tuple3<Long, String, Long>> {
        private final String host;
        private final int port;
        private final int ttl;
        private transient Jedis jedis;

        public RedisUserCatSink(String host, int port, int ttl) {
            this.host = host;
            this.port = port;
            this.ttl = ttl;
        }

        @Override
        public void open(Configuration parameters) {
            jedis = new Jedis(host, port);
        }

        @Override
        public void invoke(Tuple3<Long, String, Long> v, Context context) {
            String key = RedisKeys.rtUser(v.f0);
            jedis.hset(key, v.f1, String.valueOf(v.f2));
            jedis.expire(key, ttl);
        }

        @Override
        public void close() {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    // ---------- 参数 ----------

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String s = args[i];
            if (s.startsWith("--")) {
                String key = s.substring(2);
                if (key.contains("=")) {
                    int eq = key.indexOf('=');
                    m.put(key.substring(0, eq), key.substring(eq + 1));
                } else if (i + 1 < args.length) {
                    m.put(key, args[++i]);
                }
            }
        }
        return m;
    }

    private static String arg(Map<String, String> a, String key, String envKey, String def) {
        if (a.containsKey(key)) {
            return a.get(key);
        }
        String env = System.getenv(envKey);
        return env != null ? env : def;
    }
}
