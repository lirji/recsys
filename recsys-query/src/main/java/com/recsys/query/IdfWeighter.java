package com.recsys.query;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 词项 IDF 权重查表(R8)。离线 {@code IdfJob} 把 {@code IDF=ln((N+1)/(df+1))+1} 物化到 Redis
 * Hash {@code idf:terms},本组件按刷新间隔整表 HGETALL 到内存缓存,供 query 理解给
 * {@code TermWeight} 赋权 —— 稀有词权重更高,替代旧的恒 1.0。
 *
 * <p><b>优雅降级</b>(与本仓一贯主张一致):Redis 未注入 / 作业未跑 / 表空 / 查询异常 →
 * {@link #weight} 一律返回中性 1.0(等同接入前)。OOV/缺失词也退 1.0(保守,不放大生僻词/拼写噪声)。
 *
 * <p><b>在线/离线契约</b>:先查离线双写的 raw alias；语料中未出现的词形再用 PostgreSQL
 * {@code to_tsvector('english', raw)} 映射 canonical lexeme，确保与 FTS stemming 完全同口径。
 */
@Component
public class IdfWeighter {

    private static final Logger log = LoggerFactory.getLogger(IdfWeighter.class);
    private static final double NEUTRAL = 1.0;

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final QueryProperties props;

    private volatile Map<String, Double> rawIdf = Map.of();
    private volatile Map<String, Double> lexemeIdf = Map.of();
    private volatile long loadedAtMs = 0;
    private final Map<String, String> lexemeCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 10_000;
                }
            });

    public IdfWeighter(ObjectProvider<StringRedisTemplate> redisProvider,
                       ObjectProvider<JdbcTemplate> jdbcProvider,
                       QueryProperties props) {
        this.redisProvider = redisProvider;
        this.jdbcProvider = jdbcProvider;
        this.props = props;
    }

    /** 词项 IDF 权重;未启用 / 缺失 / OOV → 中性 1.0。 */
    public double weight(String term) {
        return weights(List.of(term)).getOrDefault(term, NEUTRAL);
    }

    /**
     * 一次 query 的批量权重。raw alias/cache miss 最多一次 PostgreSQL round-trip，避免 maxTerms 个串行查询。
     */
    public Map<String, Double> weights(Collection<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return Map.of();
        }
        if (!props.getIdf().isEnabled()) {
            Map<String, Double> neutral = new LinkedHashMap<>();
            terms.forEach(t -> neutral.put(t, NEUTRAL));
            return neutral;
        }
        current();

        List<String> misses = terms.stream()
                .filter(t -> t != null && !t.isBlank())
                .filter(t -> !rawIdf.containsKey(t) && !lexemeCache.containsKey(t))
                .distinct()
                .toList();
        canonicalizeBatch(misses);

        Map<String, Double> result = new LinkedHashMap<>();
        for (String term : terms) {
            Double raw = rawIdf.get(term);
            if (raw != null) {
                result.put(term, raw);
                continue;
            }
            String lexeme = cachedLexeme(term);
            result.put(term, lexeme == null ? NEUTRAL : lexemeIdf.getOrDefault(lexeme, NEUTRAL));
        }
        return result;
    }

    /** 到期(refreshMs)重载 IDF 表;非到期直接返回内存缓存。 */
    private void current() {
        long now = System.currentTimeMillis();
        if (now - loadedAtMs < props.getIdf().getRefreshMs() && loadedAtMs != 0) {
            return;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - loadedAtMs < props.getIdf().getRefreshMs() && loadedAtMs != 0) {
                return;
            }
            rawIdf = load(RedisKeys.IDF_TERMS, rawIdf);
            lexemeIdf = load(RedisKeys.IDF_LEXEMES, lexemeIdf);
            loadedAtMs = System.currentTimeMillis();
        }
    }

    private Map<String, Double> load(String key, Map<String, Double> previous) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return previous; // 保留旧值(通常空);无 Redis → 全退 1.0
        }
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(key);
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            Map<String, Double> m = new HashMap<>(raw.size() * 2);
            for (Map.Entry<Object, Object> e : raw.entrySet()) {
                try {
                    m.put(String.valueOf(e.getKey()), Double.parseDouble(String.valueOf(e.getValue())));
                } catch (RuntimeException ignore) {
                    // 单条脏数据跳过,不拖垮整表
                }
            }
            log.debug("IDF 表 {} 已加载 {} 个词项", key, m.size());
            return m;
        } catch (Exception e) {
            log.debug("加载 IDF 表 {} 失败,保留旧值: {}", key, e.getMessage());
            return previous;
        }
    }

    private void canonicalizeBatch(List<String> rawTerms) {
        if (rawTerms.isEmpty()) {
            return;
        }
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            return;
        }
        try {
            List<LexemeAlias> aliases = jdbc.query("""
                    SELECT raw, lexemes[1] AS lexeme
                    FROM (
                        SELECT raw, tsvector_to_array(to_tsvector('english', raw)) AS lexemes
                        FROM unnest(?::text[]) AS t(raw)
                    ) x
                    WHERE cardinality(lexemes) = 1
                    """,
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf(
                            "text", rawTerms.toArray(String[]::new))),
                    (rs, n) -> new LexemeAlias(rs.getString("raw"), rs.getString("lexeme")));
            Map<String, String> mapped = new HashMap<>();
            aliases.forEach(a -> mapped.put(a.raw(), a.lexeme()));
            // 查询成功但没有 lexeme 的 token 是 PG stopword；负缓存避免重复查库。
            rawTerms.forEach(raw -> lexemeCache.put(raw, mapped.getOrDefault(raw, "")));
        } catch (Exception e) {
            // 连接/查询瞬时失败不能负缓存，否则该词在进程生命周期内都无法恢复。
            log.debug("批量映射 English lexeme 失败 terms={}: {}", rawTerms.size(), e.getMessage());
        }
    }

    private String cachedLexeme(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cached = lexemeCache.get(raw);
        return cached == null || cached.isEmpty() ? null : cached;
    }

    record LexemeAlias(String raw, String lexeme) {
    }
}
