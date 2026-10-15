package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.BehaviorSource;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Bandit训练曝光上下文读取，不改变正反馈奖励的独立时间口径。 */
public interface BanditImpressionMapper {
    /** 保留重复曝光与原可空身份读取为0的语义，具名字段代替数组位置。 */
    record Impression(long userId, long itemId) { }

    /** 默认窗口由作业决定；非正天数沿用原全量读取，max-ts严格包含到指定秒。 */
    List<Impression> load(@Param("source") BehaviorSource source, @Param("days") int days,
                          @Param("maxTs") Long maxTs);
}
