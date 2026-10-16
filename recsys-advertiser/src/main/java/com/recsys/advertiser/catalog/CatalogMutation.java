package com.recsys.advertiser.catalog;

import com.recsys.advertiser.dto.*;

/** HTTP原操作和资源身份随命令冻结，恢复不再从新的请求或当前登录者重建输入。 */
public record CatalogMutation(CatalogProtocol.Kind kind,long resourceId,AdUpsert ad,BidwordUpsert bidword,
                              CreativeUpsert creative,AdvertiserUpsert advertiser,String status,String decision,String reason) {
    public CatalogMutation {
        if(kind==null || resourceId<0)throw new IllegalArgumentException("目录操作身份无效");
    }
}
