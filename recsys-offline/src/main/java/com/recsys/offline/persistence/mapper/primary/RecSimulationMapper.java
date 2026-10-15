package com.recsys.offline.persistence.mapper.primary;

import com.recsys.content.persistence.model.CatalogTable;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

import java.sql.Timestamp;
import java.util.List;

/** 推荐模拟只读写主源；目录白名单与原评分聚合在XML中明确表达。 */
public interface RecSimulationMapper {
    /** 可空物品ID由原算法按JDBC语义读取为0，COUNT始终非空。 */
    record RatingCount(Long itemId, long count) { }
    /** 单行事件保留曝光位次、点击/点赞时间与原混合顺序。 */
    record Event(long userId, long itemId, String action, double value, int position, Timestamp timestamp) { }

    /** 仅清理sim-rec，调用顺序仍先于目录参数校验和空目录判断。 */
    int deleteSimulation();
    /** 两张固定来源表沿用原无ORDER BY读取，不改变随机取样输入。 */
    List<Long> itemIds(@Param("source") CatalogTable source);
    /** 聚合回调在会话内消费，不将原扫描改为全量结果列表。 */
    void scanRatingCounts(ResultHandler<RatingCount> handler);
    /** 由BATCH会话加入原单次批处理，不把返回值当已提交行数。 */
    void insert(@Param("row") Event row);
}
