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
 * - 各路 recallScore 量纲不同(SEMANTIC/VECTOR 是余弦∈[0,1],HOT/TAG/COLD 是热度计数),
 *   合并前**先按每路自身最大值归一化到 [0,1]**,使跨路分数可比 —— 否则下游全局归一化会被
 *   热度计数(动辄成百上千)主导,把语义/向量路的余弦分压到接近 0,channel-boost 也救不回来。
 * - 同一 itemId 命中多路 → 保留所有来源(channels),recallScore 取各路归一化分的最大值。
 * - 任一路抛异常被 catch 降级,不影响整体;热门兜底始终生效。
 */
@Service
@EnableConfigurationProperties(RecallProperties.class)
public class MultiChannelRecallService implements RecallService {

    private static final Logger log = LoggerFactory.getLogger(MultiChannelRecallService.class);

    private final List<ChannelRecaller> recallers;
    private final RecallProperties props;

    public MultiChannelRecallService(List<ChannelRecaller> recallers, RecallProperties props) {
        this.recallers = recallers;
        this.props = props;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        // 融合模式:默认按路归一化取 max(跨路可比);搜索场景传 recall-fusion=rrf 用 RRF
        // (Reciprocal Rank Fusion)按各路排名融合 —— 对量纲迥异的检索器(BM25 vs 余弦)更鲁棒。
        boolean rrf = "rrf".equalsIgnoreCase(ctx.params().get("recall-fusion"));
        double rrfK = props.getRrfK();

        // itemId -> 合并后的条目(记录来源与召回分)
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
            if (rrf) {
                // RRF:本路按分降序定名次,贡献 1/(k+rank) 累加;名次而非原始分,免归一化、抗量纲
                List<RecallItem> sorted = new ArrayList<>(items);
                sorted.sort((a, b) -> Double.compare(b.recallScore(), a.recallScore()));
                int rank = 0;
                for (RecallItem it : sorted) {
                    rank++;
                    double contrib = 1.0 / (rrfK + rank);
                    merged.compute(it.itemId(), (id, cur) -> {
                        if (cur == null) {
                            return new Merged(contrib, it.channel());
                        }
                        cur.score += contrib;
                        if (!cur.channels.contains(it.channel())) {
                            cur.channels.add(it.channel());
                        }
                        return cur;
                    });
                }
            } else {
                // 本路自身最大分,用于把该路所有分归一化到 [0,1](跨路可比的前提)。
                double chMax = 0;
                for (RecallItem it : items) {
                    chMax = Math.max(chMax, it.recallScore());
                }
                final double denom = chMax > 0 ? chMax : 1.0;
                for (RecallItem it : items) {
                    double norm = it.recallScore() / denom;
                    merged.compute(it.itemId(), (id, cur) -> {
                        if (cur == null) {
                            return new Merged(norm, it.channel());
                        }
                        cur.score = Math.max(cur.score, norm);
                        if (!cur.channels.contains(it.channel())) {
                            cur.channels.add(it.channel());
                        }
                        return cur;
                    });
                }
            }
        }

        // RRF 累加分归一化到 [0,1],与默认模式量纲一致(下游全局归一化 + channel-boost 不受影响)
        if (rrf && !merged.isEmpty()) {
            double max = merged.values().stream().mapToDouble(m -> m.score).max().orElse(1.0);
            double d = max > 0 ? max : 1.0;
            for (Merged m : merged.values()) {
                m.score /= d;
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
            case TIGER -> 4;       // 完整生成式召回(自回归),学习型语义信号,信息量高
            case GENERATIVE -> 5;  // 生成式语义 ID 召回(前缀检索版)
            case VECTOR -> 6;
            case SEMANTIC -> 7;
            case LEXICAL -> 8;     // 词法 query 相关性(搜索),与 SEMANTIC 同属 query 内容匹配
            case TAG -> 9;
            case COLD -> 10;
            case HOT -> 11;
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
