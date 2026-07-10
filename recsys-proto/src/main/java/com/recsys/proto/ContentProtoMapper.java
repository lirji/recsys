package com.recsys.proto;

import com.recsys.content.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 边界防腐层(ACL,P2):在 gRPC wire 类型与领域 record {@link Item} 间互转。
 * content-service(出)与 rec-engine(入)两侧共用。protobuf3 无 null → 空串/空列表代之。
 */
public final class ContentProtoMapper {

    private ContentProtoMapper() {
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static com.recsys.proto.content.v1.Item toProto(Item it) {
        com.recsys.proto.content.v1.Item.Builder b = com.recsys.proto.content.v1.Item.newBuilder()
                .setItemId(it.itemId())
                .setTitle(nz(it.title()))
                .setCategory(nz(it.category()))
                .setDescription(nz(it.description()))
                .setPopularity(it.popularity());
        if (it.tags() != null) {
            for (String t : it.tags()) {
                b.addTags(nz(t));
            }
        }
        return b.build();
    }

    public static Item fromProto(com.recsys.proto.content.v1.Item p) {
        return new Item(p.getItemId(), p.getTitle(), p.getCategory(),
                new ArrayList<>(p.getTagsList()), p.getDescription(), p.getPopularity());
    }

    /** BatchGetItemsReply → id→Item(与 ContentService.findByIds 契约一致:缺失的 id 不在 map 中)。 */
    public static Map<Long, Item> toItemMap(List<com.recsys.proto.content.v1.Item> items) {
        Map<Long, Item> out = new LinkedHashMap<>(items.size());
        for (com.recsys.proto.content.v1.Item p : items) {
            out.put(p.getItemId(), fromProto(p));
        }
        return out;
    }
}
