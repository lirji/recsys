package com.recsys.recall.channel;

import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.recsys.recall.persistence.mapper.derived.SemanticCodeMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成式召回(RQ-VAE 语义 ID,docs/04 §14)。TIGER 范式的"可服务"落地版本。
 *
 * <p>离线 {@code train_rqvae.py} 用残差量化自编码器(RQ-VAE)把每个 item 的向量压成一串
 * 由粗到细的 codeword —— <b>语义 ID</b> {@code (c0, c1, c2)};共享前缀的 item 语义相近
 * (c0 同 = 粗簇,c0c1 同 = 更细,c0c1c2 同 = 最细)。由 {@code import-semantic-id} 灌入
 * {@code item_semantic_id} 表。
 *
 * <p><b>在线</b>:取用户近期正反馈 item 作种子 → 查它们的语义 ID → 检索与任一种子<b>共享前缀</b>
 * 的其它 item,按"最长公共前缀深度"打分(3=全匹配 > 2 > 1)。这是把 TIGER"自回归生成下一个
 * 语义 ID"退化成"前缀检索同簇"—— 不需要在 Java 里跑自回归 Transformer + beam search,
 * 完全契合本仓"离线烘焙 + 在线查表"的范式。完整自回归生成器为后续(见 docs/04 §14 边界)。
 *
 * <p><b>优雅降级</b>:表不存在 / 用户无种子 / 种子无语义 ID → 返回空,其他召回路兜底
 * (架构铁律:任一路失败不拖垮整体)。
 */
@Component
public class SemanticIdRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(SemanticIdRecaller.class);

    private final RecentPositiveItemsSource recentItemsSource;
    private final SemanticCodeMapper mapper;   // #3:item_semantic_id 走派生库(种子 user_behavior 读留 @Primary)
    private final RecallProperties props;

    public SemanticIdRecaller(RecentPositiveItemsSource recentItemsSource,
                              SemanticCodeMapper mapper,
                              RecallProperties props) {
        this.recentItemsSource = recentItemsSource;
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.GENERATIVE;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        try {
            List<Long> seeds = seeds(ctx, props.getQuota().getGenerativeSeed());
            if (seeds.isEmpty()) {
                return List.of();
            }
            Map<Long, int[]> seedCodes = loadCodes(seeds);
            if (seedCodes.isEmpty()) {
                return List.of();
            }
            Set<Long> seedSet = new LinkedHashSet<>(seeds);
            // 种子的粗码集合(只在共享 c0 的簇内检索候选,避免全表扫)
            Set<Integer> c0set = new LinkedHashSet<>();
            for (int[] code : seedCodes.values()) {
                c0set.add(code[0]);
            }
            Integer[] c0s = c0set.toArray(new Integer[0]);

            // 候选 = 与任一种子同粗簇(c0)的所有 item;在 Java 里按最长公共前缀深度打分
            Map<Long, Double> best = new HashMap<>();
            mapper.candidatesForCodes(c0s, context -> {
                var row = context.getResultObject();
                long itemId = row.itemId();
                if (seedSet.contains(itemId)) {
                    return; // 不召回种子本身
                }
                int[] cand = row.codes();
                int depth = 0;
                for (int[] seed : seedCodes.values()) {
                    depth = Math.max(depth, prefixDepth(seed, cand));
                    if (depth == 3) {
                        break;
                    }
                }
                if (depth > 0) {
                    best.merge(itemId, depth / 3.0, Math::max); // 归一化到 (0,1]
                }
            });

            int limit = props.getQuota().getGenerative();
            return best.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(limit)
                    .map(e -> new RecallItem(e.getKey(), e.getValue(), RecallChannel.GENERATIVE))
                    .toList();
        } catch (Exception e) {
            log.debug("生成式召回失败 user={}(表缺失?),返回空: {}", ctx.userId(), e.getMessage());
            return List.of();
        }
    }

    /** 最长公共前缀深度:c0c1c2 全同=3,c0c1 同=2,c0 同=1,否则 0。 */
    private static int prefixDepth(int[] a, int[] b) {
        if (a[0] != b[0]) {
            return 0;
        }
        if (a[1] != b[1]) {
            return 1;
        }
        if (a[2] != b[2]) {
            return 2;
        }
        return 3;
    }

    private Map<Long, int[]> loadCodes(List<Long> itemIds) {
        Long[] ids = itemIds.toArray(new Long[0]);
        Map<Long, int[]> out = new HashMap<>();
        mapper.codesForItems(ids).forEach(row -> out.put(row.itemId(), row.codes()));
        return out;
    }

    /** 种子物品:优先用编排层预取的近期正反馈(取前 n 前缀,与 SQL LIMIT n 逐项等价);未预取则回退查库。 */
    private List<Long> seeds(RecallContext ctx, int n) {
        List<Long> prefetched = ctx.recentPositiveItems();
        if (prefetched != null) {
            return prefetched.size() > n ? prefetched.subList(0, n) : prefetched;
        }
        return recentItems(ctx.userId(), n);
    }

    private List<Long> recentItems(long userId, int n) {
        return recentItemsSource.recentPositiveItems(userId, n);
    }
}
