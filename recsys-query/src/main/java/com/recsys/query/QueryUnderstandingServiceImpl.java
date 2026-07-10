package com.recsys.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.content.ItemCatalogReader;
import com.recsys.common.embedding.EmbeddingClient;
import com.recsys.common.llm.LlmClient;
import com.recsys.common.query.CategoryScore;
import com.recsys.common.query.QueryTokens;
import com.recsys.common.query.QueryUnderstandingService;
import com.recsys.common.query.StructuredQuery;
import com.recsys.common.query.TermWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * <p><b>LLM 增强(可选,docs/04 §11)</b>:若注入了就绪的 {@link LlmClient}(配置
 * {@code recsys.llm.enabled=true} + key),在分词前先用 LLM 做一次结构化理解 ——
 * <b>拼写纠错</b>(corrected 作为有效 query 替换 normalized,改善语义召回)、
 * <b>意图分类</b>(直接产出受限于 genre 词表的类目,比"标题 ILIKE 投票"更鲁棒)、
 * <b>改写扩展</b>(并入 rewrites)。LLM 未就绪/失败/返回不可解析 → 整步跳过,
 * 回落到上述纯词法逻辑,行为与接入前完全一致(每步独立降级的一贯主张)。
 *
 * 设计承接 docs/05 §4.1:把内容推荐的 userId 驱动扩展出「query 驱动」入口,
 * 复用现有 category 体系、EmbeddingClient 与 LlmClient 契约,不新增向量维度/模型。
 */
@Service
@EnableConfigurationProperties(QueryProperties.class)
public class QueryUnderstandingServiceImpl implements QueryUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(QueryUnderstandingServiceImpl.class);

    // #3:意图识别/genre 词典的 item 读经此 seam(db 直读 item / replica 读 item_local)
    private final ItemCatalogReader itemCatalog;
    private final ObjectProvider<EmbeddingClient> embeddingProvider;
    private final ObjectProvider<LlmClient> llmProvider;
    private final IdfWeighter idfWeighter;
    private final QueryProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    /** genre 小写 → 规范名,懒加载缓存(DB 不可用时为空,直接命中那一路自然失效)。 */
    private volatile Map<String, String> genreLexicon;

    public QueryUnderstandingServiceImpl(ItemCatalogReader itemCatalog,
                                         ObjectProvider<EmbeddingClient> embeddingProvider,
                                         ObjectProvider<LlmClient> llmProvider,
                                         IdfWeighter idfWeighter,
                                         QueryProperties props) {
        this.itemCatalog = itemCatalog;
        this.embeddingProvider = embeddingProvider;
        this.llmProvider = llmProvider;
        this.idfWeighter = idfWeighter;
        this.props = props;
    }

    @Override
    public StructuredQuery parse(String rawQuery, long userId) {
        String raw = rawQuery == null ? "" : rawQuery;
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return new StructuredQuery(raw, normalized, List.of(), List.of(), List.of(), null);
        }
        // LLM 增强(可选):拼写纠错 + 意图 + 改写;不可用/失败 → null,后续全走词法兜底
        LlmEnrichment llm = enrichWithLlm(normalized);
        String effective = (llm != null && !llm.corrected.isEmpty()) ? normalize(llm.corrected) : normalized;

        List<TermWeight> terms = tokenize(effective);
        List<CategoryScore> intents = (llm != null && !llm.intents.isEmpty())
                ? llm.intents                       // LLM 意图优先(已对齐 genre 词表)
                : classifyIntent(terms);            // 兜底:genre 命中 + 标题投票
        float[] embedding = embed(effective);
        List<String> rewrites = rewrite(effective, terms);
        if (llm != null) {
            for (String exp : llm.expansions) {
                if (rewrites.size() >= 1 + props.getLlm().getMaxExpansions() + props.getSynonyms().size()) {
                    break;
                }
                String n = normalize(exp);
                if (!n.isEmpty() && !rewrites.contains(n)) {
                    rewrites.add(n);
                }
            }
        }
        return new StructuredQuery(raw, effective, terms, intents, rewrites, embedding);
    }

    // ---- 1. 归一化(与离线 IdfJob 共用 QueryTokens,保证在线/离线分词一致)----
    private String normalize(String raw) {
        return QueryTokens.normalize(raw);
    }

    // ---- 2. 分词 + IDF 权重(R8)----
    private List<TermWeight> tokenize(String normalized) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String tok : QueryTokens.tokenize(normalized)) {
            if (props.getStopwords().contains(tok)) {
                continue;
            }
            seen.add(tok);
            if (seen.size() >= props.getMaxTerms()) {
                break;
            }
        }
        List<TermWeight> terms = new ArrayList<>(seen.size());
        for (String t : seen) {
            // 权重 = IDF(离线物化到 Redis,稀有词更高);未启用/缺失/OOV → 中性 1.0
            terms.add(new TermWeight(t, idfWeighter.weight(t)));
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
            // ① 词项直接命中 genre 名 → 强投票,按词项 IDF 加权(稀有/具体的 genre 提及信号更强)
            Map<String, String> lexicon = genreLexicon();
            for (TermWeight tw : terms) {
                String canonical = lexicon.get(tw.term());
                if (canonical != null) {
                    scores.merge(canonical, 3.0 * tw.weight(), Double::sum);
                }
            }
            // ② 标题投票:title 含某词项 → 该物品 category 计一票(SQL 下沉 ItemCatalogReader,item 表名按 seam 切换)
            List<String> patterns = terms.stream().map(t -> "%" + t.term() + "%").toList();
            for (ItemCatalogReader.CatCount row : itemCatalog.categoryCountsByTitleLike(patterns, 50)) {
                scores.merge(row.category(), (double) row.count(), Double::sum);
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
                for (String cat : itemCatalog.distinctCategories()) {
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

    // ---- LLM 增强(可选,优雅降级)----

    /**
     * 用 LLM 做一次结构化 query 理解。任一环节不可用(关闭 / 未注入 / 未就绪 / 调用失败 /
     * 解析失败)都返回 null,调用方据此回落纯词法链路。意图被严格对齐到本库 genre 词表
     * (LLM 自由发挥的类目若不在词表内会被丢弃),保证与下游 TAG 召回的类目体系一致。
     */
    private LlmEnrichment enrichWithLlm(String normalized) {
        if (!props.getLlm().isEnabled()) {
            return null;
        }
        LlmClient client = llmProvider.getIfAvailable();
        if (client == null || !client.isReady()) {
            return null;
        }
        Map<String, String> lexicon = genreLexicon(); // 小写 → 规范名
        try {
            String genreList = String.join(", ", new LinkedHashSet<>(lexicon.values()));
            String system = "You are a movie search query understanding engine. "
                    + "Given a user's raw search query, return ONLY a JSON object with keys: "
                    + "\"corrected\" (string: the query with spelling fixed and normalized, lowercase), "
                    + "\"intents\" (array of genre strings, most relevant first, chosen ONLY from the allowed genres), "
                    + "\"expansions\" (array of up to " + props.getLlm().getMaxExpansions()
                    + " alternative phrasings / synonym expansions of the query). "
                    + "Allowed genres: [" + genreList + "]. "
                    + "If a field is unknown, use an empty string or empty array. No prose, JSON only.";
            String user = "query: " + normalized;
            String json = client.complete(system, user);
            return parseEnrichment(json, lexicon);
        } catch (Exception e) {
            log.debug("LLM query 理解失败,降级纯词法: {}", e.getMessage());
            return null;
        }
    }

    /** 解析 LLM 的 JSON 输出;意图映射回规范 genre 名并按序赋递减分。解析异常 → null。 */
    private LlmEnrichment parseEnrichment(String json, Map<String, String> lexicon) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(json);
            String corrected = root.path("corrected").asText("").trim();

            List<CategoryScore> intents = new ArrayList<>();
            JsonNode intentNode = root.path("intents");
            if (intentNode.isArray()) {
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                int n = intentNode.size();
                for (int i = 0; i < n; i++) {
                    String g = intentNode.get(i).asText("").trim().toLowerCase(Locale.ROOT);
                    String canonical = lexicon.get(g);
                    if (canonical == null || !seen.add(canonical)) {
                        continue; // 丢弃词表外 / 重复
                    }
                    double score = Math.max(0.1, 1.0 - 0.1 * intents.size()); // 1.0, 0.9, ...
                    intents.add(new CategoryScore(canonical, score));
                    if (intents.size() >= props.getMaxIntents()) {
                        break;
                    }
                }
            }

            List<String> expansions = new ArrayList<>();
            JsonNode expNode = root.path("expansions");
            if (expNode.isArray()) {
                for (JsonNode e : expNode) {
                    String s = e.asText("").trim();
                    if (!s.isEmpty()) {
                        expansions.add(s);
                    }
                }
            }
            return new LlmEnrichment(corrected, intents, expansions);
        } catch (Exception e) {
            log.debug("LLM 输出非合法 JSON,降级纯词法: {}", e.getMessage());
            return null;
        }
    }

    /** LLM 结构化理解结果。 */
    private record LlmEnrichment(String corrected, List<CategoryScore> intents, List<String> expansions) {
    }
}
