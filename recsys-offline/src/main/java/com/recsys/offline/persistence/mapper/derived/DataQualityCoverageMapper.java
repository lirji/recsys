package com.recsys.offline.persistence.mapper.derived;

/** 覆盖率分子只能从原派生源读取，不能被主库同名表误导。 */
public interface DataQualityCoverageMapper {
    /** 沿原表行数计数，不以目录JOIN或去重静默改变覆盖率。 */
    long countItemEmbeddings();
    /** 分子与主源活跃用户分母独立读取，不改变巡检原事务边界。 */
    long countUserEmbeddings();
}
