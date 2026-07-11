package com.recsys.console.item;

import java.util.List;

/**
 * 物料元数据(控制台展示投影)。
 *
 * <p>{@link com.recsys.content.Item} 的精简投影:只保留控制台展示需要的标题/类目/标签,
 * 去掉 description/popularity 以减小载荷。让全站裸 {@code #itemId} 显示成真实标题。
 *
 * @param itemId   物品 ID
 * @param title    标题
 * @param category 类目
 * @param tags     标签
 */
public record ItemMeta(long itemId, String title, String category, List<String> tags) {
}
