package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.query.QueryTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 作业 idf:直接从 PostgreSQL {@code title_tsv} 统计 English lexeme document frequency，拟合 IDF。
 * canonical 值写 {@code idf:lexemes}；语料中的 raw token 映射到同一 lexeme 后双写
 * {@code idf:terms}，保证 movie/movies 等词形与实际 FTS 排序口径一致。
 *
 * <p><b>在线/离线契约</b>:raw token 仍用 {@link QueryTokens}，但 df 和 stemming 的真值来自与
 * {@code title_tsv/to_tsquery} 相同的 PostgreSQL {@code english} text-search config。
 * IDF = {@code ln((N+1)/(df+1)) + 1}(平滑,恒 ≥ 1,稀有词更高)。
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
        String it = ItemQuery.table(args);   // #3:item 读来源表(默认 item)

        IdfSnapshot snapshot = compute(it, minDf);
        if (snapshot.totalDocuments() == 0) {
            log.warn("item 表为空,idf:terms 未更新;先跑 --job=import-items");
            return;
        }

        // 临时 Hash 完整写好后 RENAME 替换，单表发布无空窗；先 canonical、后兼容 alias。
        replaceHash(RedisKeys.IDF_LEXEMES, snapshot.lexemes());
        replaceHash(RedisKeys.IDF_TERMS, snapshot.rawTerms());
        redis.opsForValue().set(RedisKeys.IDF_DOC_COUNT, String.valueOf(snapshot.totalDocuments()));
        log.info("idf 完成:N={} lexeme={} raw-alias={}(min-df={});stemming=postgres/english",
                snapshot.totalDocuments(), snapshot.lexemes().size(), snapshot.rawTerms().size(), minDf);
    }

    /** 包可见，供真实 PostgreSQL 集成测试锁死 English stemming/df 契约。 */
    IdfSnapshot compute(String itemTable, int minDf) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + itemTable, Integer.class);
        int total = count == null ? 0 : count;
        if (total == 0) {
            return new IdfSnapshot(0, Map.of(), Map.of());
        }

        Map<String, Long> df = new HashMap<>();
        // ts_stat 的 ndoc 是“包含该 lexeme 的文档数”；title_tsv 已按 english stemming 且文档内去重。
        jdbc.query("SELECT word, ndoc FROM ts_stat('SELECT title_tsv FROM " + itemTable + "')",
                (RowCallbackHandler) rs -> df.put(rs.getString("word"), rs.getLong("ndoc")));

        Map<String, String> lexemes = new HashMap<>(df.size() * 2);
        for (Map.Entry<String, Long> e : df.entrySet()) {
            if (e.getValue() >= minDf) {
                double idf = Math.log((double) (total + 1) / (e.getValue() + 1)) + 1.0;
                lexemes.put(e.getKey(), String.format(Locale.ROOT, "%.6f", idf));
            }
        }

        Set<String> rawTokens = new HashSet<>();
        jdbc.query("SELECT coalesce(title,'') || ' ' || coalesce(category,'') AS text FROM " + itemTable,
                (RowCallbackHandler) rs -> rawTokens.addAll(QueryTokens.tokenize(rs.getString("text"))));

        Map<String, String> rawToLexeme = mapRawTokens(rawTokens);
        Map<String, String> rawTerms = new HashMap<>(rawTokens.size() * 2 + lexemes.size());
        // canonical key 也放进兼容 Hash，旧实例即使收到已经 canonicalized 的词仍可命中。
        rawTerms.putAll(lexemes);
        rawToLexeme.forEach((raw, lexeme) -> {
            String value = lexemes.get(lexeme);
            if (value != null) {
                rawTerms.put(raw, value);
            }
        });
        return new IdfSnapshot(total, Map.copyOf(lexemes), Map.copyOf(rawTerms));
    }

    private Map<String, String> mapRawTokens(Set<String> tokens) {
        if (tokens.isEmpty()) {
            return Map.of();
        }
        List<String> all = List.copyOf(tokens);
        Map<String, String> out = new HashMap<>(all.size() * 2);
        final int batchSize = 2000;
        for (int from = 0; from < all.size(); from += batchSize) {
            List<String> batch = all.subList(from, Math.min(from + batchSize, all.size()));
            jdbc.query("""
                    SELECT raw, lexemes[1] AS lexeme
                    FROM (
                        SELECT raw, tsvector_to_array(to_tsvector('english', raw)) AS lexemes
                        FROM unnest(?::text[]) AS t(raw)
                    ) x
                    WHERE cardinality(lexemes) = 1
                    """,
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", batch.toArray(String[]::new))),
                    (RowCallbackHandler) rs -> out.put(rs.getString("raw"), rs.getString("lexeme")));
        }
        return out;
    }

    private void replaceHash(String destination, Map<String, String> values) {
        if (values.isEmpty()) {
            redis.delete(destination);
            return;
        }
        String temporary = destination + ":tmp:" + UUID.randomUUID();
        try {
            redis.opsForHash().putAll(temporary, values);
            redis.expire(temporary, Duration.ofHours(1));
            redis.rename(temporary, destination);
        } catch (RuntimeException e) {
            try {
                redis.delete(temporary);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    record IdfSnapshot(int totalDocuments, Map<String, String> lexemes, Map<String, String> rawTerms) {
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }
}
