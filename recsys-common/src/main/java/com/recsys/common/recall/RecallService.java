package com.recsys.common.recall;

import java.util.List;

/**
 * 召回服务契约(Track B 实现)。
 * 多路并行召回 → 合并去重 → 返回候选集(带 channel 标记)。
 * 实现要求:任一路失败不影响整体,热门兜底永远可用。
 */
public interface RecallService {

    /**
     * @param ctx 召回上下文
     * @return 合并去重后的候选列表;同一 itemId 命中多路时应保留其来源信息
     */
    List<RecallItem> recall(RecallContext ctx);
}
