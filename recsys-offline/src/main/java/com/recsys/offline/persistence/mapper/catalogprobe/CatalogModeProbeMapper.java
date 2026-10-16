package com.recsys.offline.persistence.mapper.catalogprobe;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** seed独立物理连接的只读准入探测，不加入默认SINGLE或共享Mapper扫描。 */
public interface CatalogModeProbeMapper {
    /** 仅0张治理表为未安装；部分安装或错误对象类型均不准入。 */
    Installation installation();
    /** 已安装后必须存在唯一且完整未登记的LEGACY控制身份。 */
    List<Mode> modes();
    /** 事务局部会话标识用于识别同一实际库的两个连接，不持久写入。 */
    String markProbe(@Param("tag") String tag);
    /** 双方会话标识就绪后清除事务内统计快照，避免把不可见误判为不同库。 */
    String clearSnapshot();
    /** 本标识须唯一可见，另一标识在同一database中必须不存在。 */
    Peers peers(@Param("self") String self,@Param("peer") String peer);
    record Installation(int tables,int regularTables) { }
    record Mode(int shardId,String mode,boolean unbound,String scriptSha256,int historyRows) { }
    record Peers(int ownCount,int peerCount,String readOnly) { }
}
