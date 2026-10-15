package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.AdEventSource;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 广告保序校准只负责读取原曝光/去重点击关联，分箱和PAVA留在作业。 */
public interface AdCalibrationMapper {
    /** 具名预测/标签替代数组下标，保留原双精度值。 */
    record Sample(double prediction, double clicked) { }
    /** 去重点击按请求及广告关联，不增加时间窗口；原预测升序保持。 */
    List<Sample> load(@Param("source") AdEventSource source);
}
