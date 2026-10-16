package com.recsys.advertiser.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.UUID;

/** 可靠写、准入、后台、发送、恢复独立显式开关；不配置时全部关闭。 */
@ConfigurationProperties("recsys.ad.catalog.reliability")
public record CatalogProperties(boolean enabled,boolean admissionEnabled,boolean workerEnabled,boolean relayEnabled,
                                 boolean recoveryEnabled,UUID sourceId,String writerName,Integer writerId) {
    public boolean operational(){return admissionEnabled || workerEnabled || relayEnabled;}
    public boolean any(){return enabled || operational() || recoveryEnabled;}
}
