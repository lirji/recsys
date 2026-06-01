package com.recsys.recengine.rerank;

import com.recsys.content.Item;

import java.util.List;
import java.util.Map;

/**
 * 重排输入:目标条数、候选的主召回来源(用于理由)、物品元数据、本次 rerank 层参数。
 *
 * @param size          需要返回的条数
 * @param recallChannel itemId -> 命中的召回路名列表(主路在首位,如 ["I2I","HOT"]),
 *                      用于 recallFrom 透传与生成推荐理由(理由取主路 = 首元素)
 * @param itemMap       itemId -> Item 元数据(类目/标题等),可能不含全部候选
 * @param params        rerank 层实验参数(如 maxSameCategory / lambda)
 */
public record RerankInput(int size,
                          Map<Long, List<String>> recallChannel,
                          Map<Long, Item> itemMap,
                          Map<String, String> params) {

    public int intParam(String key, int def) {
        String v = params.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public double doubleParam(String key, double def) {
        String v = params.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
