package com.recsys.advertiser.catalog;

import com.fasterxml.jackson.databind.*;

/** 内部命令/片段编码固定顺序；事件仍由更严格的CatalogSnapshotCodec核验。 */
public final class CatalogJson {
    private final ObjectMapper json;
    public CatalogJson(ObjectMapper json) {
        this.json=json.copy().findAndRegisterModules().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
    }
    /** 业务原文有界，失败只暴露安全分类。 */
    public byte[] encode(Object value) {
        try {byte[] b=json.writeValueAsBytes(value);CatalogProtocol.requireBytes(b);return b;}
        catch(java.io.IOException e){throw CatalogProtocol.failure(CatalogProtocol.Failure.INCONSISTENT);}
    }
    /** 仅解码受原摘要保护的持久输入，不接收不可信多态类型。 */
    public <T>T decode(byte[] bytes,Class<T> type) {
        try{return json.readValue(bytes,type);}catch(java.io.IOException e){throw CatalogProtocol.failure(CatalogProtocol.Failure.INCONSISTENT);}
    }
    public JsonNode tree(Object value){return json.valueToTree(value);}
    public String text(Object value){return new String(encode(value),java.nio.charset.StandardCharsets.UTF_8);}
}
