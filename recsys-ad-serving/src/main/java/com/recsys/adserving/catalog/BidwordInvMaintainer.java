package com.recsys.adserving.catalog;

import com.recsys.common.ad.AdCatalogEvent;
import com.recsys.common.ad.BidwordInvCodec;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 竞价词倒排维护(P1b 收尾):在 ad-serving 侧,从广告目录事件维护关键词倒排 {@code bidword:inv:{keyword}}
 * ——把倒排所有权从广告主写侧迁到广告在线消费端(replica 模式下 ad-serving 自主拥有可服务集,含关键词召回)。
 *
 * <p>口径/编码与广告主的 {@code AdIndexSyncService} 完全一致(共享 {@link BidwordInvCodec}),故与其"双写"到共享 Redis
 * 时幂等一致;Redis 按服务拆分后即由 ad-serving 独占。快照式维护需<b>老关键词 diff</b>:移除本广告在
 * "旧有、新无"关键词下的 member,再(可服务时)写当前关键词。墓碑=移除全部旧关键词。Redis 不可用 → 吞异常降级。
 */
@Component
public class BidwordInvMaintainer {

    private static final Logger log = LoggerFactory.getLogger(BidwordInvMaintainer.class);

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public BidwordInvMaintainer(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /** 按事件维护倒排。{@code oldBidwords}=应用事件前副本里的竞价词(算关键词 diff 用)。 */
    public void apply(AdCatalogEvent e, List<AdCatalogEvent.Bidword> oldBidwords) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;   // Redis 不可用:与本仓一致的降级(在线关键词路可退 DB 兜底)
        }
        try {
            Set<String> newKw = new HashSet<>();
            if (e.servable()) {
                for (AdCatalogEvent.Bidword bw : e.bidwords()) {
                    newKw.add(bw.keyword());
                }
            }
            // 1) 移除"旧有、新无"关键词下本广告的 member(墓碑 ⇒ newKw 空 ⇒ 移除全部旧关键词)
            for (AdCatalogEvent.Bidword old : oldBidwords) {
                if (!newKw.contains(old.keyword())) {
                    removeAdFromKeyword(redis, old.keyword(), e.adId());
                }
            }
            // 2) 写当前关键词(可服务)。先移除同关键词旧 member(bidwordId 可能变),再按当前出价写入。
            if (e.servable()) {
                for (AdCatalogEvent.Bidword bw : e.bidwords()) {
                    removeAdFromKeyword(redis, bw.keyword(), e.adId());
                    String member = BidwordInvCodec.encode(
                            e.adId(), bw.id(), e.itemId(), e.advertiserId(), e.qualityScore());
                    redis.opsForZSet().add(RedisKeys.bidwordInv(bw.keyword()), member, bw.bid());
                }
            }
        } catch (Exception ex) {
            log.warn("维护竞价词倒排失败 adId={}: {}", e.adId(), ex.getMessage());
        }
    }

    /** 移除某关键词 ZSet 里所有属于该广告的 member(member 以 "{adId}:" 起头,口径同 AdIndexSyncService)。 */
    private void removeAdFromKeyword(StringRedisTemplate redis, String keyword, long adId) {
        String key = RedisKeys.bidwordInv(keyword);
        Set<String> members = redis.opsForZSet().range(key, 0, -1);
        if (members == null || members.isEmpty()) {
            return;
        }
        String prefix = adId + ":";
        Object[] toRemove = members.stream().filter(m -> m.startsWith(prefix)).toArray();
        if (toRemove.length > 0) {
            redis.opsForZSet().remove(key, toRemove);
        }
    }
}
