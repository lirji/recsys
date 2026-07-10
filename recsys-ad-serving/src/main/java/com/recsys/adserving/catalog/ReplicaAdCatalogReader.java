package com.recsys.adserving.catalog;

import com.recsys.ad.AdCatalogReader;
import com.recsys.ad.AdRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

/**
 * 副本目录读(P1b + 收尾):广告在线所需的全部目录读(标题/oCPC/详情/出价/定向)都从事件构建的自有可服务副本
 * {@code ad_servable} 读,而非直读广告主分片目录库。仅 {@code recsys.ad.catalog.source=replica} 时装配并替换默认
 * {@code ShardedAdCatalogReader};广告管线 / 召回回填({@link AdCatalogReader})无感切换,一键回滚(改回 sharded)。
 */
@Component
@ConditionalOnProperty(name = "recsys.ad.catalog.source", havingValue = "replica")
public class ReplicaAdCatalogReader implements AdCatalogReader {

    private final AdServableRepository repo;

    public ReplicaAdCatalogReader(AdServableRepository repo) {
        this.repo = repo;
    }

    @Override
    public Map<Long, String> titles(Collection<Long> adIds) {
        return repo.titles(adIds);
    }

    @Override
    public Map<Long, AdRepository.OcpcParams> ocpcParams(Collection<Long> adIds) {
        return repo.ocpcParams(adIds);
    }

    @Override
    public Map<Long, AdDetail> activeAdDetails(Collection<Long> adIds) {
        return repo.activeAdDetails(adIds);
    }

    @Override
    public Map<Long, Double> bidMap(Collection<Long> adIds) {
        return repo.bidMap(adIds);
    }

    @Override
    public Map<Long, Long> audiencesByAd(Collection<Long> adIds) {
        return repo.audiencesByAd(adIds);
    }
}
