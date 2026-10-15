package com.recsys.offline.persistence.mapper.primary;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/** MovieLens导入只管理自己的scene，已有线上行为继续由原入口维护。 */
public interface BehaviorImportMapper {
    /** CSV解析后的具名记录，避免列顺序隐藏在Object数组里。 */
    record Rating(long userId, long itemId, double value, String scene, Timestamp timestamp) { }

    /** 重跑前仅清理当前导入来源，返回实际删除数供原日志使用。 */
    int deleteScene(@Param("scene") String scene);

    /** 调用方保留1000条上界；一次批次失败不撤销此前已提交批次。 */
    int insertRatings(@Param("rows") List<Rating> rows);

    /** 沿用原全表计数日志，不把导入数误作数据库总数。 */
    long count();
}
