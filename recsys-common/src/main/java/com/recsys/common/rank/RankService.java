package com.recsys.common.rank;

import java.util.List;

/**
 * 排序服务契约(Track C 实现)。
 * 对召回候选做特征装配 + 模型/规则打分,输出有序结果。
 * 关键约束:在线特征装配逻辑必须与离线训练特征一致,避免特征穿越/不一致。
 */
public interface RankService {

    /**
     * @param userId           用户 ID
     * @param candidateItemIds 召回候选 ID 列表
     * @param scene            场景
     * @return 按分数降序的排序结果
     */
    List<RankedItem> rank(long userId, List<Long> candidateItemIds, String scene);
}
