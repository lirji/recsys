package com.recsys.advertiser.persistence.mapper.primary;

import com.recsys.advertiser.catalog.CatalogProtocol.CommandKey;
import org.apache.ibatis.annotations.Param;
import java.util.UUID;

/** 初始化的SQL均显式选择物理shard；原库列表由本库函数枚举，避免逻辑广播掩盖错库。 */
public interface CatalogInitializationMapper {
    int occupy(@Param("key") CommandKey key);
    int quiesce(@Param("shard") int shard,@Param("key") CommandKey key,@Param("cluster") String cluster,
                @Param("topicId") String topicId,@Param("topic") String topic,@Param("manifest") String manifest);
    boolean layout(@Param("shard") int shard);
    String page(@Param("shard") int shard,@Param("kind") String kind,@Param("after") long after);
    int freezeTarget(@Param("shard") int shard,@Param("command") UUID command,@Param("ad") long ad,
                     @Param("owner") long owner,@Param("sha") String sha);
    Target next(@Param("shard") int shard,@Param("command") UUID command);
    int completeTarget(@Param("shard") int shard,@Param("command") UUID command,@Param("ad") long ad,@Param("result") byte[] result);
    long unfinished(@Param("shard") int shard,@Param("command") UUID command);
    int activate(@Param("shard") int shard,@Param("key") CommandKey key);
    record Target(long targetId,long targetOwnerId) { }
    record SourceTarget(long id,long owner) { }
}
