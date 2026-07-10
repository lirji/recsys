package com.recsys.rank;

import java.util.ArrayList;
import java.util.List;

/**
 * SIM 的 GSU(General Search Unit,R3)——从用户<b>长</b>行为历史里,按目标候选<b>类目硬检索</b>出
 * 最相关的最近 k 个,喂给 ESU(DIN 注意力)。纯函数,便于单测({@code SimGsuTest})。
 *
 * <p>SIM(Search-based Interest Model)相对 DIN 的关键:DIN 只吃近 ≤20 短序列,长期兴趣被截断;
 * SIM 先用 GSU 从数百上千的长历史里"检索出与本候选同类目"的子序列(每候选各不相同),
 * 再对这段更相关的历史做 ESU 注意力 —— 用极小在线开销把有效历史长度拉长一个量级。
 * 这里用"类目硬检索"(category hard-search),向量软检索为可选进阶。
 */
final class SimGsu {

    private SimGsu() {
    }

    /** 一条历史行为:itemId + 类目(用于 GSU 类目匹配)。历史按 oldest→newest 传入。 */
    record Hist(long itemId, String category) {
    }

    /**
     * 从长历史中检索与 {@code candCategory} 同类目的最近 k 个 itemId,返回 oldest→newest(与 ESU 序列口径一致)。
     * 候选类目为空 / 无同类目历史 → 空(ESU 对空序列会把兴趣池化置 0,冷类目不崩)。
     */
    static List<Long> retrieve(List<Hist> historyOldestFirst, String candCategory, int k) {
        if (historyOldestFirst == null || historyOldestFirst.isEmpty() || candCategory == null || k <= 0) {
            return List.of();
        }
        // 从最近往回扫,收集同类目 item,凑够 k 个停;再反转回 oldest→newest
        List<Long> picked = new ArrayList<>(k);
        for (int i = historyOldestFirst.size() - 1; i >= 0 && picked.size() < k; i--) {
            Hist h = historyOldestFirst.get(i);
            if (candCategory.equals(h.category())) {
                picked.add(h.itemId());
            }
        }
        // picked 现在是 newest→oldest,反转成 oldest→newest
        List<Long> out = new ArrayList<>(picked.size());
        for (int i = picked.size() - 1; i >= 0; i--) {
            out.add(picked.get(i));
        }
        return out;
    }
}
