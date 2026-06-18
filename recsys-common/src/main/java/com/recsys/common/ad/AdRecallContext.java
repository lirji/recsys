package com.recsys.common.ad;

import com.recsys.common.query.StructuredQuery;

import java.util.List;

/**
 * 广告召回上下文。承载一次广告检索所需的输入。
 *
 * @param query           结构化查询(Query 理解层产出:terms/intents/rewrites/embedding)
 * @param userId          用户 ID(定向/个性化补充)
 * @param size            候选规模目标(各路配额之和)
 * @param enabledChannels 本次启用的召回路(空 = 全开)
 */
public record AdRecallContext(StructuredQuery query,
                              long userId,
                              int size,
                              List<AdChannel> enabledChannels) {

    public AdRecallContext {
        enabledChannels = enabledChannels == null ? List.of() : enabledChannels;
    }

    /** 该路是否在本次启用(enabledChannels 为空表示全开)。 */
    public boolean isEnabled(AdChannel channel) {
        return enabledChannels.isEmpty() || enabledChannels.contains(channel);
    }
}
