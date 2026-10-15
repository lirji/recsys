package com.recsys.offline.persistence.model;

import java.util.List;

/** 跨库目录复制的六个基列；生成列由目标PG计算，NULL标签沿用原跨库空数组规则。 */
public record ItemCatalogCopyRow(long itemId, String title, String category, List<String> tags,
                                 String description, Double popularity) {
    /** 同库复制绕过该记录，保留同库SQL NULL与跨库空数组的既有区别。 */
    public ItemCatalogCopyRow {
        if (tags == null) tags = List.of();
    }
}
