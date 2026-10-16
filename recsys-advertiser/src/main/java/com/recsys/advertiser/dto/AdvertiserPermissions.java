package com.recsys.advertiser.dto;

/** 当前主体在指定作用域的操作提示；不是可复用凭据，写入口仍执行实时判权。 */
public record AdvertiserPermissions(boolean createAdvertiser, boolean reviewAds,
                                    boolean editAds, boolean manageAdvertiser) {
}
