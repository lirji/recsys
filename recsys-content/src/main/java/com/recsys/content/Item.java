package com.recsys.content;

import java.util.List;

/**
 * 物品(对应 item 表)。
 */
public record Item(
        long itemId,
        String title,
        String category,
        List<String> tags,
        String description,
        double popularity) {
}
