package com.recsys.offline.persistence.mapper.source;

import com.recsys.offline.persistence.model.AdEventLogRow;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 广告源只读分页，不进入目标事务，不使用目标MAX(id)作为提交水位。 */
public interface AdEventCopySourceMapper {
    /** 数据库/模式/表OID与服务端端点用于冻结实际来源。 */
    String identity();
    /** 正数ID追加源的当前可见上界，仅为单轮分页封顶。 */
    long upper();
    /** 非正ID不属于本协议，必须拒绝而非静默遗漏。 */
    boolean invalidIds();
    /** 源读取有固定页数和语句超时，结果不会装入无限集合。 */
    List<AdEventLogRow> page(@Param("after") long after, @Param("upper") long upper, @Param("limit") int limit);
}
