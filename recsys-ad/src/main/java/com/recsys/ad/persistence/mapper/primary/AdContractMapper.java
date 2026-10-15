package com.recsys.ad.persistence.mapper.primary;

import java.util.List;

/** GD合约仍从原主库读取，不能随广告目录转入分片源。 */
public interface AdContractMapper {
    /** 保留数据库的epoch/CPM bigint舍入，NULL数值沿用0。 */
    List<Contract> activeContracts();

    /** 合约字段具名，避免价格、时间和目标量依赖数组位置。 */
    record Contract(long contractId, long adId, long advertiserId, long guaranteed,
                    long startMillis, long endMillis, long cpmMilli) { }
}
