package com.recsys.recengine.persistence.mapper.primary;

import com.recsys.recengine.persistence.model.ExposureWrite;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 引擎原主源上的行为读取与曝光写入；不跨接派生向量源。 */
public interface EngineBehaviorMapper {
    /** 去重正反馈物品，保留原SQL对NULL item_id的结果。 */
    List<Long> seenItems(@Param("userId") long userId);
    /** 冷启动计数排除系统曝光，避免仅请求推荐就脱冷。 */
    long positiveBehaviorCount(@Param("userId") long userId);
    /** 每个已返回页面一次语句写入，约束失败时全页不提交。 */
    int appendExposures(@Param("userId") long userId, @Param("scene") String scene,
                        @Param("bucket") String bucket, @Param("action") String action,
                        @Param("rows") List<ExposureWrite> rows);
}
