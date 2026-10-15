package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.AdEventSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

import java.util.List;

/** CVR训练读取原主库事件/读仓，最早转化匹配和特征装配由作业保留。 */
public interface AdCvrInputMapper {
    /** requestId保留NULL，原Java字符串拼接形成的匹配键语义不变。 */
    record Conversion(String requestId, long adId, double epoch) { }
    /** 具名点击输入替代Object[]，NULL数值仍读取为0。 */
    record Click(String requestId, long userId, long adId, double epoch, double elapsedDays) { }

    /** 只限制转化已到达，不额外增加起始窗口或要求晚于点击。 */
    void scanConversions(@Param("source") AdEventSource source, ResultHandler<Conversion> handler);
    /** 天数非正时读取全量；保留原未来点击和数据库now()的时区/事务语义。 */
    List<Click> loadClicks(@Param("source") AdEventSource source, @Param("days") int days);
}
