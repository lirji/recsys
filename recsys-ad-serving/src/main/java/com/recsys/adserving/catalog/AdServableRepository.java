package com.recsys.adserving.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdCatalogReader;
import com.recsys.ad.AdRepository;
import com.recsys.common.ad.AdCatalogEvent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import com.recsys.adserving.persistence.mapper.addb.AdServableMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 广告可服务副本读写(P1b + 收尾)。ad-serving <b>自有</b>的目录副本 {@code ad_servable}(主数据源=recsys 库,非分片),
 * 由 {@link AdCatalogEventConsumer} 从广告目录事件幂等构建。{@code ReplicaAdCatalogReader} 经本类提供
 * 广告在线所需的<b>全部目录读</b>(标题 / oCPC / 可服务详情 / 最高出价 / 定向人群),使 ad-serving 不再跨上下文
 * 直读广告主分片目录库(DB-per-service)。
 *
 * <p>只存可服务广告:{@code servable=false} → {@link #delete(long)} 移除行,故副本即"可服务集",读侧无需再过滤状态。
 * {@code max_bid} 消费时从 bidwords 算好、免读时解析 JSON。经广告自有库Mapper(非 ShardingSphere)。
 */
@Repository
public class AdServableRepository {

    private static final Logger log = LoggerFactory.getLogger(AdServableRepository.class);

    private final AdServableMapper catalog;
    private final DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();

    /** Mapper及DDL都复用adDbDataSource，不另建连接池或切换主库。 */
    public AdServableRepository(AdServableMapper catalog, @Qualifier("adDbDataSource") DataSource dataSource) {
        this.catalog = catalog;
        this.dataSource = dataSource;
    }

    /** 新数据集登记后，关新开关也不能重新启用无版本旧写入。 */
    public void requireLegacyWritable() {
        if (catalog.projectionSchema() != null && catalog.projectionRegistered())
            throw new com.recsys.adserving.catalog.projection.CatalogProjectionProtocol.Failure(
                    com.recsys.adserving.catalog.projection.CatalogProjectionProtocol.Code.LEGACY_WRITER_FORBIDDEN);
    }

    /** 防御性自建表(已建库实例无需重跑 08_ad_servable.sql 也可用)。 */
    @PostConstruct
    void ensureTable() {
        try {
            new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/ad-serving-catalog.sql")).execute(dataSource);
        } catch (Exception e) {
            log.warn("建 ad_servable/ad_embedding 表失败(稍后消费/读会再暴露): {}", e.getMessage());
        }
    }

    /** #3:从目录事件携带的向量(pgvector ::text)幂等 upsert ad_embedding(拆库后 ad_embedding 归 ad-serving 自有库)。 */
    public void upsertEmbedding(long adId, String vecText) {
        if (vecText == null || vecText.isBlank()) {
            return;
        }
        try {
            catalog.upsertEmbedding(adId, vecText);
        } catch (Exception e) {
            log.warn("upsert ad_embedding 失败 adId={}: {}", adId, e.getMessage());
        }
    }

    /** 墓碑移除向量，缺失行仍视为幂等成功。 */
    public void deleteEmbedding(long adId) {
        try {
            catalog.deleteEmbedding(adId);
        } catch (Exception e) {
            log.debug("delete ad_embedding 失败 adId={}: {}", adId, e.getMessage());
        }
    }

    /** 幂等 upsert 一条可服务广告快照(max_bid 从 bidwords 算)。 */
    public void upsert(AdCatalogEvent e) {
        double maxBid = e.bidwords().stream().mapToDouble(AdCatalogEvent.Bidword::bid).max().orElse(0.0);
        try {
            catalog.upsert(e, maxBid, writeJson(e.bidwords()), writeJson(e.creatives()));
        } catch (Exception ex) {
            log.warn("upsert ad_servable 失败 adId={}: {}", e.adId(), ex.getMessage());
        }
    }

    /** 读该广告当前副本里的竞价词(用于消费端维护倒排时算关键词 diff)。行不存在/解析失败 → 空。 */
    public List<AdCatalogEvent.Bidword> bidwordsOf(long adId) {
        try {
            List<String> rows = catalog.bidwords(adId);
            if (rows.isEmpty() || rows.get(0) == null) {
                return List.of();
            }
            return json.readValue(rows.get(0), new TypeReference<List<AdCatalogEvent.Bidword>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 移除广告(下线/删除/未过审)。 */
    public void delete(long adId) {
        try {
            catalog.delete(adId);
        } catch (Exception ex) {
            log.warn("删 ad_servable 失败 adId={}: {}", adId, ex.getMessage());
        }
    }

    /** 副本里的可服务广告数(供验证)。 */
    public long count() {
        try {
            return catalog.count();
        } catch (Exception ex) {
            return -1;
        }
    }

    /** 目录标题回填，保留NULL标题和查询失败时已读到的条目。 */
    public Map<Long, String> titles(Collection<Long> adIds) {
        Map<Long, String> out = new HashMap<>();
        if (adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        try {
            catalog.titles(ids, context -> {
                var row = context.getResultObject(); out.put(row.adId(), row.title());
            });
        } catch (Exception ex) {
            log.warn("读 ad_servable 标题失败: {}", ex.getMessage());
        }
        return out;
    }

    /** 目录优化参数供召回后估值，缺失成本沿用0。 */
    public Map<Long, AdRepository.OcpcParams> ocpcParams(Collection<Long> adIds) {
        Map<Long, AdRepository.OcpcParams> out = new HashMap<>();
        if (adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        try {
            catalog.optimizations(ids, context -> {
                var row = context.getResultObject();
                out.put(row.adId(), new AdRepository.OcpcParams(row.type(), row.cpa()));
            });
        } catch (Exception ex) {
            log.warn("读 ad_servable oCPC 参数失败: {}", ex.getMessage());
        }
        return out;
    }

    /** 可服务详情。adIds=null → 全部(副本仅存可服务广告,故无需再过滤状态)。 */
    public Map<Long, AdCatalogReader.AdDetail> activeAdDetails(Collection<Long> adIds) {
        Map<Long, AdCatalogReader.AdDetail> out = new HashMap<>();
        try {
            if (adIds != null && adIds.isEmpty()) return out;
            catalog.details(adIds == null ? null : adIds.toArray(Long[]::new), context -> {
                var row = context.getResultObject();
                out.put(row.adId(), new AdCatalogReader.AdDetail(row.itemId(), row.advertiserId(), row.quality()));
            });
        } catch (Exception ex) {
            log.warn("读 ad_servable 详情失败: {}", ex.getMessage());
        }
        return out;
    }

    /** 最高出价(消费时已算好存 max_bid 列)。adIds=null → 全部。 */
    public Map<Long, Double> bidMap(Collection<Long> adIds) {
        Map<Long, Double> out = new HashMap<>();
        try {
            if (adIds != null && adIds.isEmpty()) return out;
            catalog.bids(adIds == null ? null : adIds.toArray(Long[]::new), context -> {
                var row = context.getResultObject(); out.put(row.adId(), row.bid());
            });
        } catch (Exception ex) {
            log.warn("读 ad_servable 出价失败: {}", ex.getMessage());
        }
        return out;
    }

    /** 定向人群包(audience_id 非空的广告)。 */
    public Map<Long, Long> audiencesByAd(Collection<Long> adIds) {
        Map<Long, Long> out = new HashMap<>();
        if (adIds == null || adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        try {
            catalog.audiences(ids, context -> {
                var row = context.getResultObject(); out.put(row.adId(), row.audienceId());
            });
        } catch (Exception ex) {
            log.warn("读 ad_servable 定向失败: {}", ex.getMessage());
        }
        return out;
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
