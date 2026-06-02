package com.recsys.query;

import com.recsys.common.embedding.EmbeddingClient;
import com.recsys.common.query.CategoryScore;
import com.recsys.common.query.QueryUnderstandingService;
import com.recsys.common.query.StructuredQuery;
import com.recsys.common.query.TermWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Query 理解层默认实现。把原始查询解析为 {@link StructuredQuery},分五步,**每步独立降级**:
 *
 * <ol>
 *   <li><b>归一化</b>:小写、去标点、压空格。</li>
 *   <li><b>分词 + 权重</b>:切词 → 去停用词 → 去重 → 截断 maxTerms;权重恒 1.0(IDF 留 TODO)。</li>
 *   <li><b>意图识别</b>:① 词项直接命中 genre 名(强投票)② 标题投票
 *       {@code item.title ILIKE ANY(terms)} 聚合 category;合并归一化 → 过阈值取 TopN。
 *       DB 不可用 → 意图为空,不影响其余字段。</li>
 *   <li><b>向量化</b>:可选注入 {@link EmbeddingClient},失败/缺失/关闭 → embedding=null
 *       (当前 Gemini 403、本地 BGE 未实现,故默认就是 null,链路不受影响)。</li>
 *   <li><b>改写</b>:归一化串 + 同义词扩展(配置 {@code recsys.query.synonyms});MVP 最小。</li>
 * </ol>
 *
 * 设计承接 docs/05 §4.1:把内容推荐的 userId 驱动扩展出「query 驱动」入口,
 * 复用现有 category 体系与 EmbeddingClient 契约,不新增向量维度/模型。
 */
@Service
@EnableConfigurationProperties(QueryProperties.class)
public class QueryUnderstandingServiceImpl implements QueryUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(QueryUnderstandingServiceImpl.class);

    /** 非字母数字(含中文)一律视为分隔符。 */
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final JdbcTemplate jdbc;
    private final ObjectProvider<EmbeddingClient> embeddingProvider;
    private final QueryProperties props;

    /** genre 小写 → 规范名,懒加载缓存(DB 不可用时为空,直接命中那一路自然失效)。 */
    private volatile Map<String, String> genreLexicon;

    public QueryUnderstandingServiceImpl(JdbcTemplate jdbc,
                                         ObjectProvider<EmbeddingClient> embeddingProvider,
                                         QueryProperties props) {
        this.jdbc = jdbc;
        this.embeddingProvider = embeddingProvider;
        this.props = props;
    }

    @Override
    public StructuredQuery parse(String rawQuery, long userId) {
        String raw = rawQuery == null ? "" : rawQuery;
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return new StructuredQuery(raw, normalized, List.of(), List.of(), List.of(), null);
        }
        List<TermWeight> terms = tokenize(normalized);
        List<CategoryScore> intents = classifyIntent(terms);
        float[] embedding = embed(normalized);
        List<String> rewrites = rewrite(normalized, terms);
        return new StructuredQuery(raw, normalized, terms, intents, rewrites, embedding);
    }

    // ---- 1. 归一化 ----
    private String normalize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).trim();
        s = NON_WORD.matcher(s).replaceAll(" ");
        return s.trim().replaceAll("\\s+", " ");
    }

    // ---- 2. 分词 + 权重 ----
    private List<TermWeight> tokenize(String normalized) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String tok : normalized.split(" ")) {
            if (tok.isEmpty() || props.getStopwords().contains(tok)) {
                continue;
            }
            seen.add(tok);
            if (seen.size() >= props.getMaxTerms()) {
                break;
            }
        }
        List<TermWeight> terms = new ArrayList<>(seen.size());
        for (String t : seen) {
            terms.add(new TermWeight(t, 1.0)); // TODO: 接 IDF / term importance
        }
        return terms;
    }

    // ---- 3. 意图识别 ----
    private List<CategoryScore> classifyIntent(List<TermWeight> terms) {
        if (terms.isEmpty()) {
            return List.of();
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        try {
            // ① 词项直接命中 genre 名 → 强投票
            Map<String, String> lexicon = genreLexicon();
            for (TermWeight tw : terms) {
                String canonical = lexicon.get(tw.term());
                if (canonical != null) {
                    scores.merge(canonical, 3.0, Double::sum);
                }
            }
            // ② 标题投票:title 含某词项 → 该物品 category 计一票
            String[] patterns = terms.stream().map(t -> "%" + t.term() + "%").toArray(String[]::new);
            List<Map<String, Object>> rows = jdbc.query(
                    "SELECT category, count(*) AS c FROM item " +
                    "WHERE title ILIKE ANY (?) AND category IS NOT NULL " +
                    "GROUP BY category ORDER BY c DESC LIMIT 50",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", patterns)),
                    (rs, n) -> Map.of("category", rs.getString("category"), "c", rs.getLong("c")));
            for (Map<String, Object> row : rows) {
                scores.merge((String) row.get("category"), ((Long) row.get("c")).doubleValue(), Double::sum);
            }
        } catch (Exception e) {
            log.debug("意图识别失败(DB 不可用?),降级返回已得意图: {}", e.getMessage());
        }
        if (scores.isEmpty()) {
            return List.of();
        }
        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        return scores.entrySet().stream()
                .map(e -> new CategoryScore(e.getKey(), e.getValue() / max)) // 归一化到 (0,1]
                .filter(cs -> cs.score() >= props.getIntentMinScore())
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(props.getMaxIntents())
                .toList();
    }

    /** 懒加载 genre 词典:DISTINCT item.category。失败返回空(直接命中路失效,标题投票仍工作)。 */
    private Map<String, String> genreLexicon() {
        Map<String, String> local = genreLexicon;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (genreLexicon != null) {
                return genreLexicon;
            }
            Map<String, String> built = new LinkedHashMap<>();
            try {
                for (String cat : jdbc.queryForList(
                        "SELECT DISTINCT category FROM item WHERE category IS NOT NULL", String.class)) {
                    built.put(cat.toLowerCase(Locale.ROOT), cat);
                }
            } catch (Exception e) {
                log.debug("加载 genre 词典失败,直接命中意图路暂失效: {}", e.getMessage());
            }
            genreLexicon = built;
            return built;
        }
    }

    // ---- 4. 向量化(可选,优雅降级)----
    private float[] embed(String normalized) {
        if (!props.isEmbeddingEnabled()) {
            return null;
        }
        EmbeddingClient client = embeddingProvider.getIfAvailable();
        if (client == null) {
            return null;
        }
        try {
            float[] v = client.embedText(normalized);
            return (v == null || v.length == 0) ? null : v;
        } catch (Exception e) {
            log.debug("查询向量化失败(配额/网络/未实现),降级 embedding=null: {}", e.getMessage());
            return null;
        }
    }

    // ---- 5. 改写 / 同义扩展(MVP 最小)----
    private List<String> rewrite(String normalized, List<TermWeight> terms) {
        LinkedHashSet<String> rewrites = new LinkedHashSet<>();
        rewrites.add(normalized);
        if (props.isRewriteEnabled() && !props.getSynonyms().isEmpty()) {
            // 逐词项查同义词,命中则整体替换生成一条扩展查询
            for (TermWeight tw : terms) {
                String syn = props.getSynonyms().get(tw.term());
                if (syn != null && !syn.equalsIgnoreCase(tw.term())) {
                    rewrites.add(normalized.replace(tw.term(), syn.toLowerCase(Locale.ROOT)));
                }
            }
        }
        return new ArrayList<>(rewrites);
    }
}
