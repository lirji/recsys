package com.recsys.recall.persistence.mapper.derived;

import com.recsys.recall.persistence.model.SemanticCode;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

import java.util.List;

/** 语义码只从派生库读取，不跨库连接行为表。 */
public interface SemanticCodeMapper {

    /** 输入数组按bigint[]绑定，保留原ANY成员语义。 */
    List<SemanticCode> codesForItems(@Param("itemIds") Long[] itemIds);

    /** 候选逐行交给评分器，不额外构建整个粗簇的Java结果列表。 */
    void candidatesForCodes(@Param("codes") Integer[] codes, ResultHandler<SemanticCode> handler);

    /** 生成结果先精确匹配三级码，保持原最多20项的边界。 */
    List<Long> matchingThreeLevels(@Param("c0") int c0, @Param("c1") int c1, @Param("c2") int c2);

    /** 精确匹配为空后才回退两级。 */
    List<Long> matchingTwoLevels(@Param("c0") int c0, @Param("c1") int c1);

    /** 两级匹配仍为空后才回退一级。 */
    List<Long> matchingFirstLevel(@Param("c0") int c0);
}
