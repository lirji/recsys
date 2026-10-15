package com.recsys.offline.persistence.mapper.primary;

import com.recsys.content.persistence.model.CatalogTable;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

import java.util.List;

/** IDF语料读取与英文词干映射交由原PostgreSQL配置，Java不另行近似stemming。 */
public interface IdfCorpusMapper {
    /** ts_stat的ndoc是包含词干的文档数，不是词在全文出现的次数。 */
    record Frequency(String word, long documents) { }
    /** 仅接收PostgreSQL判定恰好一个lexeme的原token映射。 */
    record RawLexeme(String raw, String lexeme) { }

    /** 保持原Integer文档数及空语料快速返回口径。 */
    Integer countDocuments(@Param("source") CatalogTable source);
    /** 词干统计流式消费到原df映射，来源只能是两个固定目录。 */
    void scanFrequencies(@Param("source") CatalogTable source, ResultHandler<Frequency> handler);
    /** title/category分别补空串后拼接，供共用QueryTokens生成别名。 */
    void scanRawText(@Param("source") CatalogTable source, ResultHandler<String> handler);
    /** 保持单批2000词的text[]绑定，过滤停用词和多词干结果。 */
    void mapRawTokens(@Param("tokens") List<String> tokens, ResultHandler<RawLexeme> handler);
}
