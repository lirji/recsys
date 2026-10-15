package com.recsys.behavior.persistence.mapper.primary;

import com.recsys.behavior.persistence.model.BehaviorWrite;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 行为权威存储，CLICK的部分唯一约束继续负责最终去重。 */
public interface BehaviorMapper {
    /** 影响行数为0表示重复曝光点击，服务不得再次计指标或发布事件。 */
    int insert(BehaviorWrite row);
    /** 列表区分不存在和存在但bucket为NULL，两者对曝光ID验证的含义不同。 */
    List<String> attributedBuckets(@Param("exposureId") String exposureId, @Param("userId") long userId,
                                    @Param("itemId") long itemId);
}
