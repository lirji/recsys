package com.recsys.offline.persistence.mapper.sharded;

import org.apache.ibatis.session.ResultHandler;

/** oCPC广告配置从原逻辑分片读取，不能用主库快照替代全部物理分片。 */
public interface OcpcTargetMapper {
    /** 目标继续由数据库按广告主取正目标均值。 */
    record Target(long advertiserId, double targetCpa) { }
    /** 64位广告身份用于主源数组绑定，不经浮点转换。 */
    record Owner(long adId, long advertiserId) { }
    /** 仅OCPC且目标大于0的广告参与控制。 */
    void scanTargets(ResultHandler<Target> handler);
    /** 与目标查询使用相同筛选，保留原独立两次读取边界。 */
    void scanOwners(ResultHandler<Owner> handler);
}
