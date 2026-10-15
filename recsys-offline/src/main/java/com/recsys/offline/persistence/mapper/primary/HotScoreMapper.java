package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.BehaviorSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/** 热榜聚合继续在主源执行，不把原行为明细拉入内存。 */
public interface HotScoreMapper {
    /** SQL投影显式保留原getLong(NULL)=0及各行为加权后的分数。 */
    record Score(long itemId, double hot) { }

    /** 封闭来源和绑定参数保持原时间窗口、严格秒上界及榜单上限。 */
    void scan(@Param("source") BehaviorSource source, @Param("recentDays") int recentDays,
              @Param("maxTs") Long maxTs, @Param("limit") int limit, ResultHandler<Score> handler);
}
