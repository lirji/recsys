package com.recsys.offline.persistence.mapper.primary;

import com.recsys.content.persistence.model.CatalogTable;
import com.recsys.offline.persistence.model.AdEventSource;
import com.recsys.offline.persistence.model.BehaviorSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

import java.util.List;

/** 主源巡检分母、校准样本与类目分布，派生向量数量不从本Mapper读取。 */
public interface DataQualityInputMapper {
    /** 具名输入避免平行数组在构造过程中错位，PG分桶及排序保持原口径。 */
    record CalibrationBin(long count, double meanPrediction, double actualRate) { }
    /** 原类别集合不排序或补入其他目录类目，缺失类目由作业按基线对齐。 */
    record CategoryCount(String category, long count) { }
    /** 目录分母保留原COUNT(*)，不提前按身份去重。 */
    long countItems(@Param("items") CatalogTable items);
    /** 所有行为的独立非NULL用户均参与，不额外增加时间或正反馈过滤。 */
    long countUsers(@Param("behavior") BehaviorSource behavior);
    /** PostgreSQL width_bucket沿原十桶规则，点击按请求和广告去重。 */
    List<CalibrationBin> loadCalibrationBins(@Param("source") AdEventSource source);
    /** days=null为全历史，0和负数保留真实下界，用于原先两次独立查询。 */
    void scanCategories(@Param("behavior") BehaviorSource behavior, @Param("items") CatalogTable items,
                        @Param("days") Integer days, ResultHandler<CategoryCount> handler);
}
