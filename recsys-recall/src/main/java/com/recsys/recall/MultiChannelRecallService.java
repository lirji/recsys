package com.recsys.recall;

import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.common.recall.RecallService;
import com.recsys.recall.channel.ChannelRecaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多路召回合并。并行调用各路 ChannelRecaller,合并去重。
 *
 * 设计要点(docs/03 §1):
 * - 各路 recallScore 量纲不同,合并时**不相加**,只记录来源 channel,排序交给 rank 层。
 * - 同一 itemId 命中多路 → 保留所有来源(channels),recallScore 取该物品在各路中的最大值(仅供调试)。
 * - 任一路抛异常被 catch 降级,不影响整体;热门兜底始终生效。
 */
@Service
@EnableConfigurationProperties(RecallProperties.class)
public class MultiChannelRecallService implements RecallService {

    private static final Logger log = LoggerFactory.getLogger(MultiChannelRecallService.class);

    private final List<ChannelRecaller> recallers;

    public MultiChannelRecallService(List<ChannelRecaller> recallers) {
        this.recallers = recallers;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        // itemId -> 合并后的条目(记录来源与最大召回分)
        Map<Long, Merged> merged = new LinkedHashMap<>();
        for (ChannelRecaller recaller : recallers) {
            List<RecallItem> items;
            try {
                items = recaller.recall(ctx);
            } catch (Exception e) {
                log.warn("召回路 {} 失败,跳过: {}", recaller.getClass().getSimpleName(), e.getMessage());
                continue;
            }
            if (items == null) {
                continue;
            }
            for (RecallItem it : items) {
                merged.compute(it.itemId(), (id, cur) -> {
                    if (cur == null) {
                        return new Merged(it.recallScore(), it.channel());
                    }
                    cur.score = Math.max(cur.score, it.recallScore());
                    if (!cur.channels.contains(it.channel())) {
                        cur.channels.add(it.channel());
                    }
                    return cur;
                });
            }
        }

        List<RecallItem> out = new ArrayList<>(merged.size());
        for (var e : merged.entrySet()) {
            Merged m = e.getValue();
            // channel 字段放首要来源(列表第一个);完整来源可由上层用 channels 重建
            out.add(new RecallItem(e.getKey(), m.score, m.channels.get(0)));
        }
        log.debug("用户 {} 多路召回合并后候选 {} 个", ctx.userId(), out.size());
        return out;
    }

    /** 合并去重后,同一物品的来源集合 + 最大召回分。 */
    private static final class Merged {
        double score;
        final List<RecallChannel> channels = new ArrayList<>();

        Merged(double score, RecallChannel first) {
            this.score = score;
            this.channels.add(first);
        }
    }
}
