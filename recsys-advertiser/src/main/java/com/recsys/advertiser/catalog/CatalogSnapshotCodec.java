package com.recsys.advertiser.catalog;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.recsys.common.ad.CatalogEventCodec;
import com.recsys.common.ad.VersionedAdCatalogEvent;

/** 确定性JSON只在新事件首次产生时编码；relay/replay必须直接使用持久原文。 */
public final class CatalogSnapshotCodec {
    private final ObjectMapper json;

    public CatalogSnapshotCodec(ObjectMapper mapper) {
        json=mapper.copy().findAndRegisterModules();
        json.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true);
        json.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
        json.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,false);
        json.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,true);
        json.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,true);
        json.configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT,false);
        json.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION,true);
    }

    /** 首次冻结完整UTF-8字节；数组必须已经按业务ID排序且无截断。 */
    public byte[] encode(VersionedAdCatalogEvent event) {
        CatalogEventCodec.validate(event);
        try {
            byte[] bytes=json.writeValueAsBytes(event);CatalogEventCodec.digest(bytes);return bytes;
        }catch(java.io.IOException failure){throw new IllegalArgumentException("目录事件编码失败");}
    }

    /** 查询/恢复核验原文，不把损坏或未知schema转成空快照/删除。 */
    public VersionedAdCatalogEvent decode(byte[] key,byte[] original) {
        CatalogEventCodec.digest(original);
        try {
            var event=json.readValue(original,VersionedAdCatalogEvent.class);CatalogEventCodec.verify(event,key,original);return event;
        }catch(java.io.IOException failure){throw new IllegalArgumentException("目录事件解码失败");}
    }
}
