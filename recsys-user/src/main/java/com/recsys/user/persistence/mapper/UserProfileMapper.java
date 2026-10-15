package com.recsys.user.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 用户画像的数据库入口；合并 JSONB 的规则在 XML 中显式保持，不用全行更新覆盖其他画像键。 */
public interface UserProfileMapper {

    /** 同一用户重复设置只更新 categories，其余画像与主键保持。 */
    int updateInterests(@Param("userId") long userId, @Param("categoriesJson") String categoriesJson);

    /** PostgreSQL 展开数组保留原有顺序和重复项；形状非法时仍由服务层执行既有降级。 */
    List<String> findInterests(@Param("userId") long userId);
}
