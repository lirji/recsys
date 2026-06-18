package com.recsys.common.ad;

/**
 * 竞价词倒排 ZSet 成员编解码——离线写(SeedAdsJob)与在线读(JdbcAdRecallService)的契约,
 * 类比 SparseFeatureEncoder 之于排序。
 *
 * <p>{@code bidword:inv:{keyword}} 的 member 自包含召回所需的全部字段(避免二次回表):
 * {@code adId:bidwordId:itemId:advertiserId:quality},score = 出价(bid)。
 * 只索引 active 广告;广告主级暂停/超预算由在线 pacing 层兜底过滤。
 */
public final class BidwordInvCodec {

    private BidwordInvCodec() {
    }

    /** 编码成 ZSet member(不含 bid,bid 走 score)。 */
    public static String encode(long adId, long bidwordId, long itemId, long advertiserId, double quality) {
        return adId + ":" + bidwordId + ":" + itemId + ":" + advertiserId + ":" + quality;
    }

    /**
     * 解码 ZSet member + score(出价)为候选。keyword 是否属于原始词项由上层判定 channel。
     * 解析失败返回 null(脏数据跳过,不拖垮召回)。
     */
    public static AdCandidate decode(String member, double bid, AdChannel channel) {
        try {
            String[] p = member.split(":");
            if (p.length < 5) {
                return null;
            }
            long adId = Long.parseLong(p[0]);
            long bidwordId = Long.parseLong(p[1]);
            long itemId = Long.parseLong(p[2]);
            long advertiserId = Long.parseLong(p[3]);
            double quality = Double.parseDouble(p[4]);
            return new AdCandidate(adId, itemId, advertiserId, bidwordId, bid, channel, bid, quality);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
