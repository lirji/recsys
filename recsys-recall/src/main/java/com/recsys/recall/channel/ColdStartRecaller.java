package com.recsys.recall.channel;

import com.recsys.common.content.ItemCatalogReader;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷启动类目探索召回:按 {@code item.category} 分组,每类目取热度 Top-k,跨类目铺开。
 *
 * <p>用于新用户(无历史)的兴趣探索——纯热门会全是同一两个大类目,无法试探用户偏好;
 * 本路用窗口函数在每个类目内取前 k 名。默认按 rank 升序交错(各类目 rank1、再 rank2…)最大化覆盖。
 *
 * <p><b>contextual bandit(UCB)升级</b>:启用 {@link ColdStartBandit} 后,类目按 UCB 分排序 ——
 * 历史正反馈率高(exploit)+ 欠曝光(explore)的类目优先浮现,而非无差别铺开;类目内仍按热度名次
 * (rn)细分。命中后行为回流 → 离线 {@code cold-bandit-stats} 更新类目统计,越试越准。
 * bandit 关闭 / 无统计 → 退回 rn 交错(旧行为)。下游冷启动强多样性重排仍保证类目覆盖。
 *
 * <p>该路默认仅由编排在冷启动判定命中时启用(见 enabledChannels),普通请求不会触发。
 */
@Component
public class ColdStartRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(ColdStartRecaller.class);

    private final ItemCatalogReader itemCatalog;   // #3:热路径 item 读经此 seam(db 直读 item / replica 读 item_local)
    private final RecallProperties props;
    private final ColdStartBandit bandit;

    public ColdStartRecaller(ItemCatalogReader itemCatalog, RecallProperties props, ColdStartBandit bandit) {
        this.itemCatalog = itemCatalog;
        this.props = props;
        this.bandit = bandit;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.COLD;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        int perCat = props.getQuota().getColdPerCategory();
        int limit = props.getQuota().getCold();
        boolean useBandit = bandit.isEnabled();
        try {
            // 每个类目内按热度取前 perCat 名(rn),整体按 (rn, 热度) 交错;各行带 category/rank 供 bandit 打分。
            // 窗口函数 SQL 已下沉到 ItemCatalogReader,item 表名按 seam 切换。
            List<ItemCatalogReader.ColdItem> rows = itemCatalog.coldStartByCategory(perCat, limit);
            if (rows.isEmpty()) {
                return List.of();
            }
            // 每个「不同类目」只算一次 UCB 分(避免逐行打 Redis)
            Map<String, Double> catUcb = new HashMap<>();
            if (useBandit) {
                for (ItemCatalogReader.ColdItem r : rows) {
                    catUcb.computeIfAbsent(r.category(), bandit::score);
                }
            }
            List<RecallItem> out = new ArrayList<>(rows.size());
            for (ItemCatalogReader.ColdItem r : rows) {
                // bandit:类目 UCB 主导,+0.001/rank 用类目内热度名次细分;关闭则退回按热度打分
                double score = useBandit
                        ? catUcb.getOrDefault(r.category(), 0.0) + 0.001 / r.rank()
                        : r.popularity();
                out.add(new RecallItem(r.itemId(), score, RecallChannel.COLD));
            }
            return out;
        } catch (Exception e) {
            log.warn("冷启动探索召回失败: {}", e.getMessage());
            return List.of();
        }
    }
}
