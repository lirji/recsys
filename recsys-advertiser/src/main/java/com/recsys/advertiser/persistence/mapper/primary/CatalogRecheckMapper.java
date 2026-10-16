package com.recsys.advertiser.persistence.mapper.primary;

import com.recsys.advertiser.catalog.CatalogProtocol.StepRow;
import com.recsys.advertiser.catalog.CatalogOutboxRepository.EventRow;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** STOPPED分片原事实逐页核验；仅最终同revision核验成功才能清除该分片停止原因。 */
public interface CatalogRecheckMapper {
    Stop stop(@Param("shard") int shard);
    List<EventRow> events(@Param("shard") int shard,@Param("after") String after);
    List<StepRow> steps(@Param("shard") int shard,@Param("after") String after);
    boolean receiptValid(@Param("shard") int shard,@Param("step") String step);
    int resume(@Param("shard") int shard,@Param("revision") long revision,@Param("reason") String reason);
    record Stop(long revision,String stopReason) { }
}
