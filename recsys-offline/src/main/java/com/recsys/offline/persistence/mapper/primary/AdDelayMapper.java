package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.AdEventSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/** 延迟模型读取已到达转化的原关联输入，异常延迟过滤和拟合仍由作业执行。 */
public interface AdDelayMapper {
    /** 具名天数保留旧JDBC读取NULL为0，与后续Java护栏顺序一致。 */
    record Delay(double days) { }
    /** 不去重关联行，天数窗口仅约束点击，转化只限制已到达。 */
    void scan(@Param("source") AdEventSource source, @Param("days") int days, ResultHandler<Delay> handler);
}
