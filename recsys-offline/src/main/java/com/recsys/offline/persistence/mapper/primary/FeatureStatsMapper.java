package com.recsys.offline.persistence.mapper.primary;

import com.recsys.content.persistence.model.CatalogTable;
import com.recsys.offline.persistence.model.BehaviorSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/** 特征物化的评分聚合保持在主源，逐行消费沿用原Redis写入及失败边界。 */
public interface FeatureStatsMapper {
    /** count包含NULL评分，平均值和总体标准差仍由PostgreSQL按原口径计算。 */
    record ItemRatings(long itemId, long count, double average, double deviation) { }
    /** 可空身份和空均值显式保留原JDBC读取为0的语义。 */
    record UserRatings(long userId, long count, double average) { }
    /** 类目非空过滤仍在SQL中，统计总数不额外排除NULL评分。 */
    record CategoryRatings(long userId, String category, long count, double average) { }

    /** 空评分源返回NULL，归一化分母沿原作业兜底规则。 */
    Double maxItemRatingCount(@Param("behavior") BehaviorSource behavior);
    /** 物品特征在结果会话内逐行发布，不改变为全量中间列表。 */
    void scanItemRatings(@Param("behavior") BehaviorSource behavior, ResultHandler<ItemRatings> handler);
    /** 用户计数与类目参与深度使用同一原最大值口径。 */
    Double maxUserRatingCount(@Param("behavior") BehaviorSource behavior);
    /** 只扫描RATING，不额外加入正反馈阈值。 */
    void scanUserRatings(@Param("behavior") BehaviorSource behavior, ResultHandler<UserRatings> handler);
    /** 两个来源独立选择固定表，缺失目录项沿原inner join排除。 */
    void scanCategoryRatings(@Param("behavior") BehaviorSource behavior, @Param("items") CatalogTable items,
                             ResultHandler<CategoryRatings> handler);
}
