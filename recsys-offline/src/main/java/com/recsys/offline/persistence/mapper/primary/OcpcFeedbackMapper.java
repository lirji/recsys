package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.AdEventSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/** 主源花费和已到达转化输入，广告主映射及延迟修正仍由作业承担。 */
public interface OcpcFeedbackMapper {
    /** 全NULL价格沿原getDouble读取为0，不能将查询失败转换为0。 */
    record Spend(long adId, double amount) { }
    /** elapsed按点击时间计算，不是转化到达的年龄。 */
    record Conversion(long adId, double elapsedDays) { }
    /** 点击与曝光按原普通JOIN关联，保留重复输入的乘积。 */
    void scanSpend(@Param("source") AdEventSource source, @Param("days") int days,
                   @Param("adIds") Long[] adIds, ResultHandler<Spend> handler);
    /** 转化须已到达，但点击只受下界限制；days非正仍沿原epoch下界。 */
    void scanConversions(@Param("source") AdEventSource source, @Param("days") int days,
                         @Param("adIds") Long[] adIds, ResultHandler<Conversion> handler);
}
