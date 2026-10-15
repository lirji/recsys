package com.recsys.ad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.recsys.ad.persistence.mapper.sharded.AdCatalogMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 默认目录读:直读广告主分片目录库(ShardingSphere 次数据源 {@code adShardingJdbc})。
 * 与 P1b 之前完全等价,是绞杀者迁移的安全默认与回滚落点
 * ({@code recsys.ad.catalog.source} 缺省或 = {@code sharded} 时生效)。
 *
 * <p>本类负责广告目录结果映射，SQL集中在 {@link AdCatalogMapper},不依赖 {@code AdRepository} —— 从而
 * {@code AdRepository} 反过来可注入本接口做召回回填(activeAdDetails/bidMap)而无环。口径:只取
 * 广告 active + 审核 approved + 广告主非 paused。
 */
@Component
@ConditionalOnProperty(name = "recsys.ad.catalog.source", havingValue = "sharded", matchIfMissing = true)
public class ShardedAdCatalogReader implements AdCatalogReader {

    private static final Logger log = LoggerFactory.getLogger(ShardedAdCatalogReader.class);

    private final AdCatalogMapper mapper;

    public ShardedAdCatalogReader(AdCatalogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<Long, String> titles(Collection<Long> adIds) {
        Map<Long, String> out = new HashMap<>();
        if (adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        try {
            mapper.titles(ids, context -> {
                var row = context.getResultObject();
                out.put(row.adId(), row.title());
            });
        } catch (Exception e) {
            log.warn("取广告标题失败: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public Map<Long, AdRepository.OcpcParams> ocpcParams(Collection<Long> adIds) {
        Map<Long, AdRepository.OcpcParams> out = new HashMap<>();
        if (adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        try {
            mapper.optimizations(ids, context -> {
                var row = context.getResultObject();
                out.put(row.adId(), new AdRepository.OcpcParams(row.type(), row.targetCpa()));
            });
        } catch (Exception e) {
            log.warn("取广告 oCPC 参数失败,退 CPC: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public Map<Long, AdDetail> activeAdDetails(Collection<Long> adIds) {
        Map<Long, AdDetail> out = new HashMap<>();
        try {
            if (adIds != null && adIds.isEmpty()) return out;
            mapper.activeDetails(adIds == null ? null : adIds.toArray(Long[]::new), context -> {
                var row = context.getResultObject();
                out.put(row.adId(), new AdDetail(row.itemId(), row.advertiserId(), row.quality()));
            });
        } catch (Exception e) {
            log.warn("取广告详情失败: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public Map<Long, Double> bidMap(Collection<Long> adIds) {
        Map<Long, Double> out = new HashMap<>();
        try {
            if (adIds != null && adIds.isEmpty()) return out;
            mapper.bids(adIds == null ? null : adIds.toArray(Long[]::new), context -> {
                var row = context.getResultObject();
                out.put(row.adId(), row.bid());
            });
        } catch (Exception e) {
            log.warn("取广告出价失败: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public Map<Long, Long> audiencesByAd(Collection<Long> adIds) {
        Map<Long, Long> out = new HashMap<>();
        if (adIds == null || adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        try {
            mapper.audiences(ids, context -> {
                var row = context.getResultObject();
                out.put(row.adId(), row.audienceId());
            });
        } catch (Exception e) {
            log.warn("取广告定向人群失败(退不定向): {}", e.getMessage());
        }
        return out;
    }
}
