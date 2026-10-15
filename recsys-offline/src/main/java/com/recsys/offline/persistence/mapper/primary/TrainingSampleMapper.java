package com.recsys.offline.persistence.mapper.primary;

import com.recsys.content.persistence.model.CatalogTable;
import com.recsys.offline.persistence.model.BehaviorSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

import java.util.List;

/** 自然推荐训练样本共用输入；时间排序、标签归因和采样规则仍由各作业负责。 */
public interface TrainingSampleMapper {
    /** 评分事件保留旧JDBC可空数值读取为0，以及目录未命中的空类目。 */
    record RatingEvent(long userId, long itemId, double value, long ts, String category) { }
    /** 类目保持NULL与空串，重复物品由原消费顺序决定最终映射。 */
    record CatalogCategory(long itemId, String category) { }
    /** 热度下限1由原数据库表达式计算，不在Java重新解释NULL/负数。 */
    record WeightedItem(long itemId, double popularity) { }
    /** 位次按显式position、value转整型、0的原优先级读取。 */
    record Impression(long userId, long itemId, int position, long ts) { }
    /** 归因输入保留全部四类反馈，避免与其他作业的正反馈过滤口径混用。 */
    record Response(long userId, long itemId, String action, double value, long ts) { }

    /** 三种样本共用评分及左连接类目；保留原扫描顺序，作业随后按事件秒排序。 */
    List<RatingEvent> ratings(@Param("source") BehaviorSource source, @Param("table") CatalogTable table);
    /** 在会话内逐行组装类目映射，不增加全量中间列表。 */
    void scanCategories(@Param("table") CatalogTable table, ResultHandler<CatalogCategory> handler);
    /** 在会话内逐行组装原加权池，不排序或去重以维持负采样序列。 */
    void scanWeightedItems(@Param("table") CatalogTable table, ResultHandler<WeightedItem> handler);
    /** 曝光不在SQL排序，维持后续合并事件流同秒先曝光的原处理规则。 */
    List<Impression> impressions(@Param("source") BehaviorSource source);
    /** 保留重复与NULL时间读取0，由作业按曝光后的窗口判断标签。 */
    void scanResponses(@Param("source") BehaviorSource source, ResultHandler<Response> handler);
}
