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
            // 分层 A/B / 冷启动可只启用部分召回路;enabledChannels 为空表示全开
            if (!ctx.isEnabled(recaller.channel())) {
                continue;
            }
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
            // 主召回路按信息量优先级选取(而非命中顺序):个性化路 > 偏好/探索 > 热门兜底,
            // 让推荐理由更贴切(如一个热门片同时被 I2I 命中,应标 I2I 而非 HOT)
            RecallChannel primary = m.channels.stream()
                    .min(java.util.Comparator.comparingInt(MultiChannelRecallService::priority))
                    .orElse(m.channels.get(0));
            // 主路按优先级排首位,其余按命中顺序跟随,全集透传给下游(理由/调试/未来融合加权)
            List<RecallChannel> ordered = new ArrayList<>();
            ordered.add(primary);
            for (RecallChannel c : m.channels) {
                if (c != primary) {
                    ordered.add(c);
                }
            }
            out.add(new RecallItem(e.getKey(), m.score, primary, ordered));
        }
        log.debug("用户 {} 多路召回合并后候选 {} 个", ctx.userId(), out.size());
        return out;
    }

    /** 主召回路优先级(数字越小越优先);兜底性越强越靠后。 */
    private static int priority(RecallChannel c) {
        return switch (c) {
            case I2I -> 0;
            case SWING -> 1;
            case U2U -> 2;
            case TWO_TOWER -> 3;   // 学行为的个性化向量召回,信息量高,优先于内容向量
            case VECTOR -> 4;
            case SEMANTIC -> 5;
            case TAG -> 6;
            case COLD -> 7;
            case HOT -> 8;
        };
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
