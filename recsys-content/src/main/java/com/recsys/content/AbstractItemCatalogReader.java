package com.recsys.content;

import com.recsys.common.content.ItemCatalogReader;
import com.recsys.common.query.TermWeight;
import com.recsys.content.persistence.mapper.ItemCatalogMapper;
import com.recsys.content.persistence.model.CatalogTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 目录读取只保留查询权重选择；数据库语义集中在 Mapper XML。
 * 两种来源复用查询契约，异常继续交给调用方按既有规则降级。
 */
public abstract class AbstractItemCatalogReader implements ItemCatalogReader {

    protected final ItemCatalogMapper mapper;

    protected AbstractItemCatalogReader(ItemCatalogMapper mapper) {
        this.mapper = mapper;
    }

    /** 来源由部署配置选择，不能接受请求传入的表名。 */
    protected abstract CatalogTable itemTable();

    @Override
    public List<ScoredId> lexicalSearch(String query, List<TermWeight> terms, int limit) {
        List<TermWeight> boosted = terms == null ? List.of() : terms.stream()
                .filter(t -> t != null && t.term() != null && !t.term().isBlank())
                .filter(t -> Double.isFinite(t.weight()) && t.weight() > 1.0)
                .toList();
        if (boosted.isEmpty()) {
            return lexicalSearch(query, limit);
        }
        // IDF=1 表示中性权重；仅增量权重参与候选加分，保持旧评分尺度。
        List<String> termArray = boosted.stream().map(TermWeight::term).toList();
        Double[] boostArray = boosted.stream().map(t -> t.weight() - 1.0).toArray(Double[]::new);
        return mapper.weightedLexicalSearch(itemTable(), query, termArray, boostArray, limit);
    }

    @Override
    public List<ScoredId> lexicalSearch(String query, int limit) {
        return mapper.lexicalSearch(itemTable(), query, limit);
    }

    @Override
    public List<CatItem> byCategories(Collection<String> categories, int limit) {
        return mapper.byCategories(itemTable(), new ArrayList<>(categories), limit);
    }

    @Override
    public List<ColdItem> coldStartByCategory(int perCategory, int limit) {
        return mapper.coldStartByCategory(itemTable(), perCategory, limit);
    }

    @Override
    public List<ScoredId> hotByPopularity(int limit) {
        return mapper.hotByPopularity(itemTable(), limit);
    }

    @Override
    public List<String> recentTitles(long userId, int limit) {
        return mapper.recentTitles(itemTable(), userId, limit);
    }

    @Override
    public List<CatId> recentRatedCategories(long userId, int limit) {
        return mapper.recentRatedCategories(itemTable(), userId, limit);
    }

    @Override
    public List<CatCount> categoryCountsByTitleLike(List<String> ilikePatterns, int limit) {
        return mapper.categoryCountsByTitleLike(itemTable(), new ArrayList<>(ilikePatterns), limit);
    }

    @Override
    public List<String> distinctCategories() {
        return mapper.distinctCategories(itemTable());
    }
}
