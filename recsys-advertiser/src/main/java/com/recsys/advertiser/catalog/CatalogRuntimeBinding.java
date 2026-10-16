package com.recsys.advertiser.catalog;

import com.recsys.advertiser.persistence.mapper.primary.CatalogLocalMapper;
import com.recsys.common.ad.VersionedAdCatalogEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;

/** 两个真实路由控制行与schema/源/写实例登记一致后才允许普通运行；恢复专用装配允许未登记。 */
public final class CatalogRuntimeBinding {
    private final CatalogProperties properties;
    private final CatalogLocalMapper mapper;
    private final ObjectMapper json;
    public CatalogRuntimeBinding(CatalogProperties properties,CatalogLocalMapper mapper,ObjectMapper json) {
        this.properties=properties;this.mapper=mapper;this.json=json;verify(properties.operational());
    }
    /** 此读取不是跨库事务；每个业务步骤仍由本库guard提供最后一道写入约束。 */
    public List<CatalogLocalMapper.Binding> verify(boolean ready) {
        var values=new ArrayList<CatalogLocalMapper.Binding>();
        for(int shard=0;shard<2;shard++) {
            var value=mapper.binding(shard);
            if(value==null || value.shardId()!=shard || !CatalogSchemaInstaller.checksum().equals(mapper.schemaChecksum(shard))
                    || !mapper.compatibleSchema(shard))throw failure(Failure.NOT_READY);
            if(value.sourceId()!=null && (!properties.sourceId().equals(value.sourceId()) || !TOPOLOGY.equals(value.topologyVersion())))throw failure(Failure.INCONSISTENT);
            if(ready && (!"ACTIVE".equals(value.mode()) || !value.initializationReady() || value.stopReason()!=null || value.sourceId()==null
                    || !VersionedAdCatalogEvent.TOPIC.equals(value.topicName()) || value.clusterId()==null || value.topicId()==null || value.initializingCommandId()==null))throw failure(Failure.NOT_READY);
            if(ready || value.sourceId()!=null)verifyManifest(value.writerManifest());
            values.add(value);
        }
        if(ready && (!Objects.equals(values.get(0).initializingCommandId(),values.get(1).initializingCommandId())
                || !Objects.equals(values.get(0).clusterId(),values.get(1).clusterId()) || !Objects.equals(values.get(0).topicId(),values.get(1).topicId())))throw failure(Failure.INCONSISTENT);
        return List.copyOf(values);
    }
    /** 初始化原请求也必须使用当前实例已显式配置的唯一writer登记。 */
    public void verifyManifest(String manifest) {
        try {
            var tree=json.readTree(manifest);var ids=new HashSet<Integer>();
            if(!tree.isObject() || tree.isEmpty() || tree.size()>1024)throw failure(Failure.INCONSISTENT);
            for(var entry:tree.properties())if(!entry.getValue().isIntegralNumber() || !entry.getValue().canConvertToInt()
                    || entry.getValue().intValue()<0 || entry.getValue().intValue()>1023 || !ids.add(entry.getValue().intValue()))throw failure(Failure.INCONSISTENT);
            if(!tree.has(properties.writerName()) || tree.get(properties.writerName()).intValue()!=properties.writerId())throw failure(Failure.INCONSISTENT);
        } catch(java.io.IOException error){throw failure(Failure.INCONSISTENT);}
    }
}
