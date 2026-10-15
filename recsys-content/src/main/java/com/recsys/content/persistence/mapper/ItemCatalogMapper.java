package com.recsys.content.persistence.mapper;

import com.recsys.common.content.ItemCatalogReader.*;
import com.recsys.content.persistence.model.CatalogTable;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 目录检索共享同一 SQL 契约，来源只能从封闭枚举选择。 */
public interface ItemCatalogMapper {

    /** 等权全文排序保持 PostgreSQL 分词及并列顺序。 */
    List<ScoredId> lexicalSearch(@Param("source") CatalogTable source, @Param("query") String query, @Param("limit") int limit);

    /** 查询词权重由业务层筛选，数组通过类型处理器绑定。 */
    List<ScoredId> weightedLexicalSearch(@Param("source") CatalogTable source, @Param("query") String query,
            @Param("terms") List<String> terms, @Param("boosts") Double[] boosts, @Param("limit") int limit);

    /** 保持旧查询的空集合错误边界与原始热度排序。 */
    List<CatItem> byCategories(@Param("source") CatalogTable source, @Param("categories") List<String> categories, @Param("limit") int limit);

    /** 每类先取排名，再按原始热度合并，避免在应用侧全量加载。 */
    List<ColdItem> coldStartByCategory(@Param("source") CatalogTable source, @Param("perCategory") int perCategory, @Param("limit") int limit);

    /** NULL 热度映射为零但排序仍遵循数据库原有 NULL 顺序。 */
    List<ScoredId> hotByPopularity(@Param("source") CatalogTable source, @Param("limit") int limit);

    /** 行为过滤与时间顺序属于现有推荐输入契约。 */
    List<String> recentTitles(@Param("source") CatalogTable source, @Param("userId") long userId, @Param("limit") int limit);

    /** 仅返回达到既有正向评分阈值的类目。 */
    List<CatId> recentRatedCategories(@Param("source") CatalogTable source, @Param("userId") long userId, @Param("limit") int limit);

    /** 模式按 text[] 绑定，不能成为 SQL 文本。 */
    List<CatCount> categoryCountsByTitleLike(@Param("source") CatalogTable source, @Param("patterns") List<String> patterns, @Param("limit") int limit);

    /** 排除未知类目，保持既有去重集合语义。 */
    List<String> distinctCategories(@Param("source") CatalogTable source);
}
