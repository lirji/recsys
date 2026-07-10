package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.query.QueryTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 作业 idf:从 {@code item} 标题+类目统计词项 document frequency,拟合 IDF 物化到 Redis
 * Hash {@code idf:terms}(field=词项、value=IDF),供在线 query 理解给 {@code TermWeight} 赋权(R8)。
 *
 * <p><b>在线/离线契约</b>:分词用 {@link QueryTokens}(与在线 {@code QueryUnderstandingServiceImpl} 同源),
 * 否则在线查表词形与此处统计的词形对不上。IDF = {@code ln((N+1)/(df+1)) + 1}(平滑,恒 ≥ 1,稀有词更高)。
 *
 * <p>参数:{@code --min-df}(df 低于此的词不写,压表体积,默认 1=全写)。
 */
@Component
public class IdfJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(IdfJob.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public IdfJob(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public String name() {
        return "idf";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int minDf = intArg(args, "min-df", 1);

        Map<String, Integer> df = new HashMap<>();
        int[] n = {0};
        // 每个 item 的标题+类目视为一篇文档;去重词项后各计一次 df(文档频率而非词频)
        jdbc.query("SELECT coalesce(title,'') || ' ' || coalesce(category,'') AS text FROM item", rs -> {
            n[0]++;
            Set<String> tokens = new HashSet<>(QueryTokens.tokenize(rs.getString("text")));
            for (String t : tokens) {
                df.merge(t, 1, Integer::sum);
            }
        });

        int total = n[0];
        if (total == 0) {
            log.warn("item 表为空,idf:terms 未更新;先跑 --job=import-items");
            return;
        }

        Map<String, String> hash = new HashMap<>(df.size() * 2);
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            if (e.getValue() < minDf) {
                continue;
            }
            double idf = Math.log((double) (total + 1) / (e.getValue() + 1)) + 1.0;
            hash.put(e.getKey(), String.format(Locale.ROOT, "%.6f", idf));
        }

        // 原子替换:删旧表再整表写入 + 记录语料文档数
        redis.delete(RedisKeys.IDF_TERMS);
        if (!hash.isEmpty()) {
            redis.opsForHash().putAll(RedisKeys.IDF_TERMS, hash);
        }
        redis.opsForValue().set(RedisKeys.IDF_DOC_COUNT, String.valueOf(total));
        log.info("idf 完成:N={} 词项={}(min-df={});在线 query 理解将据此给 TermWeight 赋权",
                total, hash.size(), minDf);
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }
}
