package com.recsys.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Query 理解层可调参数(recsys.query.*),env / Nacos 可覆盖。
 */
@ConfigurationProperties(prefix = "recsys.query")
public class QueryProperties {

    /** 单次查询保留的最大词项数(去停用词、去重后截断)。 */
    private int maxTerms = 16;

    /** 意图类目置信分阈值,低于此值丢弃。 */
    private double intentMinScore = 0.1;

    /** 意图类目最多保留数。 */
    private int maxIntents = 3;

    /** 是否启用向量化(调 EmbeddingClient);关掉则 embedding 恒 null,省一次外呼。 */
    private boolean embeddingEnabled = true;

    /** 是否启用改写/同义扩展。 */
    private boolean rewriteEnabled = true;

    /** 英文停用词(MovieLens 标题为英文);默认一组常见词,可配置覆盖。 */
    private Set<String> stopwords = new LinkedHashSet<>(Set.of(
            "a", "an", "the", "of", "and", "or", "to", "in", "on", "for",
            "with", "is", "are", "be", "movie", "movies", "film", "films"));

    /** 同义词扩展表:词 → 等价词(用于改写)。如 {@code scary -> horror}。 */
    private Map<String, String> synonyms = Map.of();

    public int getMaxTerms() {
        return maxTerms;
    }

    public void setMaxTerms(int maxTerms) {
        this.maxTerms = maxTerms;
    }

    public double getIntentMinScore() {
        return intentMinScore;
    }

    public void setIntentMinScore(double intentMinScore) {
        this.intentMinScore = intentMinScore;
    }

    public int getMaxIntents() {
        return maxIntents;
    }

    public void setMaxIntents(int maxIntents) {
        this.maxIntents = maxIntents;
    }

    public boolean isEmbeddingEnabled() {
        return embeddingEnabled;
    }

    public void setEmbeddingEnabled(boolean embeddingEnabled) {
        this.embeddingEnabled = embeddingEnabled;
    }

    public boolean isRewriteEnabled() {
        return rewriteEnabled;
    }

    public void setRewriteEnabled(boolean rewriteEnabled) {
        this.rewriteEnabled = rewriteEnabled;
    }

    public Set<String> getStopwords() {
        return stopwords;
    }

    public void setStopwords(Set<String> stopwords) {
        this.stopwords = stopwords;
    }

    public Map<String, String> getSynonyms() {
        return synonyms;
    }

    public void setSynonyms(Map<String, String> synonyms) {
        this.synonyms = synonyms;
    }
}
