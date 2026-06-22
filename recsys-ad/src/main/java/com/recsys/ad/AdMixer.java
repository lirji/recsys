package com.recsys.ad;

import com.recsys.common.ad.FeedEntry;
import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.dto.RecommendItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 混排(docs/05 §4.8):把赞助广告按 Ad Load 规则(位次 + 密度上限)插入自然推荐结果。
 *
 * <p>规则:在配置的 {@code slots} 位次放广告(最多 {@code maxAds} 条),其余位次填自然结果;
 * <b>去重</b> —— 广告关联 item 若已作为自然结果出现则跳过(反之亦然),避免同一内容既自然又广告。
 * 自然结果耗尽则停止(不硬凑广告,防"满屏广告")。Ad Load 关闭 / 无广告 → 纯自然流。
 *
 * <p>纯函数、无副作用(不读写 Redis/DB),频控与计费由编排层在混排前后处理,便于测试。
 */
@Service
public class AdMixer {

    private final AdProperties props;

    public AdMixer(AdProperties props) {
        this.props = props;
    }

    /**
     * @param organic 自然推荐结果(已排序)
     * @param ads     候选广告(已竞价 + 频控过滤,按位次/eCPM 序)
     * @param size    信息流目标条数
     * @return 混排后的信息流(按 position 升序)
     */
    public List<FeedEntry> mix(List<RecommendItem> organic, List<SponsoredAd> ads, int size) {
        AdProperties.AdLoad cfg = props.getAdLoad();
        List<FeedEntry> out = new ArrayList<>(size);
        Set<Long> shown = new HashSet<>();

        if (!cfg.isEnabled() || ads == null || ads.isEmpty()) {
            for (RecommendItem o : organic) {
                if (out.size() >= size) {
                    break;
                }
                if (shown.add(o.itemId())) {
                    out.add(organicEntry(o, out.size() + 1));
                }
            }
            return out;
        }

        Set<Integer> slots = new HashSet<>(cfg.getSlots());
        int orgIdx = 0, adIdx = 0, adsPlaced = 0, pos = 1;
        while (out.size() < size) {
            boolean wantAd = slots.contains(pos) && adsPlaced < cfg.getMaxAds() && adIdx < ads.size();
            if (wantAd) {
                while (adIdx < ads.size() && shown.contains(ads.get(adIdx).itemId())) {
                    adIdx++;   // 跳过 item 已展示的广告(去重)
                }
                if (adIdx < ads.size()) {
                    SponsoredAd a = ads.get(adIdx++);
                    out.add(adEntry(a, pos));
                    shown.add(a.itemId());
                    adsPlaced++;
                    pos++;
                    continue;
                }
            }
            while (orgIdx < organic.size() && shown.contains(organic.get(orgIdx).itemId())) {
                orgIdx++;      // 跳过已作为广告展示的 item
            }
            if (orgIdx >= organic.size()) {
                break;         // 自然结果耗尽,停止(不硬凑广告)
            }
            RecommendItem o = organic.get(orgIdx++);
            out.add(organicEntry(o, pos));
            shown.add(o.itemId());
            pos++;
        }
        return out;
    }

    private static FeedEntry organicEntry(RecommendItem o, int pos) {
        return new FeedEntry(false, o.itemId(), 0L, pos, o.score(), o.reason(), o.recallFrom());
    }

    private static FeedEntry adEntry(SponsoredAd a, int pos) {
        return new FeedEntry(true, a.itemId(), a.adId(), pos, a.ecpm(), "赞助", List.of("AD"));
    }
}
