package com.recsys.offline.persistence.mapper.sharded;

import org.apache.ibatis.session.ResultHandler;

/** 广告与物品关联只从原逻辑分片数据源读取，覆盖全部物理分片。 */
public interface AdItemMapper {
    /** 保留64位身份精度，不将广告主身份误作物品身份。 */
    record Link(long adId, long itemId) { }
    /** 无物品关联的广告不进入样本，消费顺序和重复广告的覆盖规则沿用原查询。 */
    void scanLinks(ResultHandler<Link> handler);
}
