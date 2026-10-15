package com.recsys.ad.persistence.mapper.addb;

import com.recsys.ad.persistence.model.UpliftAssignmentRow;
import org.apache.ibatis.annotations.Param;

/** 采样的advisory lock、窗口检查和唯一约束继续位于同一条SQL。 */
public interface UpliftAssignmentMapper {
    /** 只有实际插入1行才允许控制组隐藏广告，冲突或待观测窗口均返回0。 */
    int insertIfEligible(@Param("lockKey") String lockKey, @Param("row") UpliftAssignmentRow row);
}
