package com.recsys.common.recall;

import java.util.List;

/**
 * 单条召回结果。
 *
 * @param itemId      物品 ID
 * @param recallScore 召回分(各路量纲不同,合并时不可直接相加,交由排序层统一打分)
 * @param channel     主召回路(多路命中时按信息量优先级选取,见 MultiChannelRecallService)
 * @param channels    命中的全部召回路(去重,主路 {@code channel} 排首位)。多路召回合并后填全集;
 *                    单路构造时即 {@code [channel]}。用于把"多路命中"信号透传给排序/重排/解释。
 */
public record RecallItem(long itemId, double recallScore, RecallChannel channel, List<RecallChannel> channels) {

    public RecallItem {
        channels = (channels == null || channels.isEmpty()) ? List.of(channel) : List.copyOf(channels);
    }

    /** 便捷构造:单路召回(各路 Recaller 使用),channels 即 [channel]。 */
    public RecallItem(long itemId, double recallScore, RecallChannel channel) {
        this(itemId, recallScore, channel, List.of(channel));
    }
}
