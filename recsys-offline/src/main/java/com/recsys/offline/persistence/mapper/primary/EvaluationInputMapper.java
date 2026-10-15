package com.recsys.offline.persistence.mapper.primary;

import com.recsys.content.persistence.model.CatalogTable;
import com.recsys.offline.persistence.model.BehaviorSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/** 评估、校准和融合调参共用原始评分输入，切分与指标口径留在作业层。 */
public interface EvaluationInputMapper {
    /** 保留旧JDBC空数值读取0及PG时间转整秒语义，评分十分位舍入仍由原算法完成。 */
    record Rating(long userId, long itemId, double value, long ts) { }
    /** 评估热度下限为0，与负采样池下限1的查询明确区分。 */
    record Popularity(long itemId, double popularity) { }

    /** 不连接目录，缺失目录物品仍是历史/测试集输入，不在SQL过滤低分或重复事件。 */
    void scanRatings(@Param("source") BehaviorSource source, ResultHandler<Rating> handler);
    /** 逐行累计总热度，重复身份仍按原消费顺序覆盖Map而每行贡献总和。 */
    void scanCatalog(@Param("table") CatalogTable table, ResultHandler<Popularity> handler);
}
