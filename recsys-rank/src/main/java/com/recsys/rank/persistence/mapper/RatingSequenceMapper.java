package com.recsys.rank.persistence.mapper;

import com.recsys.rank.persistence.model.RatingSequenceRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** DIN/DIEN共享评分序列SQL，缓存与模型输入顺序仍由各服务保持。 */
public interface RatingSequenceMapper {

    /** 先在数据库按时间取最近的强评分事件，再由服务反转成模型输入顺序。 */
    List<RatingSequenceRow> latestRatedItems(@Param("userId") long userId, @Param("limit") int limit);
}
