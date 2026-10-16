package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import com.recsys.common.ad.BidwordInvCodec;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.recsys.content.persistence.model.CatalogTable;
import com.recsys.offline.persistence.mapper.primary.SeedItemMapper;
import com.recsys.offline.persistence.mapper.derived.SeedItemVectorMapper;
import com.recsys.offline.persistence.mapper.addb.SeedAdVectorMapper;
import com.recsys.offline.persistence.mapper.sharded.SeedAdMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 作业 seed-ads:造 mock 搜索广告(教学场景复用现有电影 item 当广告创意,docs/05 §3)。
 *
 * <p>产出:
 * <ul>
 *   <li>{@code advertiser}:N 个广告主,日预算分层(对数均匀);</li>
 *   <li>{@code ad}:M 条广告挂在随机 item 上,quality_score ∈ [0.7,1.0];</li>
 *   <li>{@code bidword}:每条广告从标题分词 + 类目 genre 名生成竞价词,出价对数正态(均值≈1元);</li>
 *   <li>{@code ad_embedding}:从 item_embedding 拷贝该广告关联 item 的向量(供 SEMANTIC_AD);</li>
 *   <li>Redis {@code bidword:inv:{keyword}}:竞价词倒排 ZSet(自包含 member,见 BidwordInvCodec)。</li>
 * </ul>
 *
 * <p>参数:--ads(默认 800)、--advertisers(默认 30)、--seed(默认 42)、--clear(先清空广告相关表+倒排)。
 * 幂等:ad_id/advertiser_id 由序号确定性生成,配 --clear 可重复跑。
 */
@Component
public class SeedAdsJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(SeedAdsJob.class);

    private final SeedItemMapper catalog;
    private final SeedAdVectorMapper adDb;
    private final SeedItemVectorMapper derived;
    private final SeedAdMapper sharded;
    private final StringRedisTemplate redis;
    private final com.recsys.offline.persistence.CatalogSeedAdmission admission;

    /** 四路Mapper复用原逻辑数据源，种子算法和Redis编排保持在作业。 */
    public SeedAdsJob(SeedItemMapper catalog, SeedAdVectorMapper adDb,
                      SeedItemVectorMapper derived, SeedAdMapper sharded, StringRedisTemplate redis,
                      com.recsys.offline.persistence.CatalogSeedAdmission admission) {
        this.catalog = catalog;
        this.adDb = adDb;
        this.derived = derived;
        this.sharded = sharded;
        this.redis = redis;
        this.admission=admission;
    }

    @Override
    public String name() {
        return "seed-ads";
    }

    /** 参数先于清理验证；清理失败不得继续生成并声称完整成功。 */
    @Override
    public void run(ApplicationArguments args) {
        int numAds = JobArguments.integer(args, "ads", 800);
        int numAdvertisers = JobArguments.integer(args, "advertisers", 30);
        int creativesPerAd = JobArguments.integer(args, "creatives-per-ad", 3);
        long seed = JobArguments.integer(args, "seed", 42);
        if (numAds <= 0 || numAdvertisers <= 0 || creativesPerAd <= 0) {
            throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        }
        CatalogTable it = ItemQuery.source(args);
        // --clear也必须先通过两物理库只读准入，不能先删adDb或Redis再被目录guard挡下。
        admission.requireLegacy();
        if (args.containsOption("clear")) {
            clear();
        }
        Random rnd = new Random(seed);

        // 取一批随机 item 作为广告创意(优先有向量的 item,让 SEMANTIC_AD 可用)
        List<long[]> items = new ArrayList<>(); // [itemId]
        List<String> titles = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        // #3 拆库:item(主库)与 item_embedding(派生库)可能不同库,拆两读 + Java 排序,等价原 ORDER BY has_vec DESC, random()
        java.util.Set<Long> withVec = new java.util.HashSet<>(
                derived.itemIds());
        List<SeedItemMapper.Item> pool = catalog.items(it);
        java.util.Collections.shuffle(pool, rnd);   // random()
        pool.sort((a, b) -> Boolean.compare(withVec.contains(b.itemId()), withVec.contains(a.itemId())));  // has_vec DESC(稳定排序保随机)
        for (SeedItemMapper.Item row : pool.subList(0, Math.min(numAds, pool.size()))) {
            items.add(new long[]{row.itemId()});
            titles.add(row.title());
            categories.add(row.category());
        }
        if (items.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_GENERATION_INPUT_EMPTY);
        }
        int m = items.size();

        // 1. 广告主:日预算对数均匀分布在 [50, 5000] 元(分片库:按 advertiser_id 落 ds_0/ds_1)
        for (int a = 1; a <= numAdvertisers; a++) {
            double budget = Math.round(50 * Math.pow(100, rnd.nextDouble())); // 50..5000
            sharded.insertAdvertiser(a, "advertiser-" + a, budget);
        }

        // 2/3. 广告 + 竞价词 + Redis 倒排。显式 id 经分片键(advertiser_id/ad_id)路由;bidword.id 用自增计数
        //      (不依赖 RETURNING——ShardingSphere + PG RETURNING 不稳;显式给 id,keygen 不触发)。
        int bidwordCount = 0;
        long bidwordSeq = 0;
        List<long[]> adItem = new ArrayList<>(m); // [adId, itemId] 供随后在主库灌 ad_embedding
        for (int i = 0; i < m; i++) {
            long adId = i + 1;
            long advertiserId = (i % numAdvertisers) + 1;
            long itemId = items.get(i)[0];
            String title = titles.get(i) == null ? "" : titles.get(i);
            double quality = round2(0.7 + 0.3 * rnd.nextDouble());
            // ~30% 广告设为 oCPC:广告主只给目标转化成本 target_cpa∈[5,20]元(余下按 CPC 手动出价)
            boolean ocpc = (i % 10) < 3;
            String optType = ocpc ? "OCPC" : "CPC";
            Double targetCpa = ocpc ? round2(5 + 15 * rnd.nextDouble()) : null;
            sharded.insertAd(new SeedAdMapper.Ad(adId, advertiserId, itemId, title,
                    "https://example.com/ad/" + adId, quality, optType, targetCpa));
            adItem.add(new long[]{adId, itemId});

            // DCO 创意(docs/05 §7 M7):每个广告 N 套创意(标题变体),多臂老虎机在线择优展示
            String[] variants = creativeTitles(title, creativesPerAd);
            for (int v = 0; v < variants.length; v++) {
                // creative_id 不传 → ShardingSphere Snowflake keygen 填充(seed 不需要回读该 id)
                sharded.insertCreative(adId, variants[v], "https://example.com/ad/" + adId + "?c=" + v);
            }

            Set<String> keywords = keywords(title, categories.get(i));
            for (String kw : keywords) {
                double bid = round2(clamp(Math.exp(rnd.nextGaussian() * 0.5), 0.2, 10.0)); // 对数正态≈1元
                String matchType = kw.contains(" ") ? "PHRASE" : "EXACT";
                long bidwordId = ++bidwordSeq;
                sharded.insertBidword(bidwordId, adId, kw, matchType, bid);
                bidwordCount++;
                // Redis 倒排:member 自包含,score=bid
                String member = BidwordInvCodec.encode(adId, bidwordId, itemId, advertiserId, quality);
                redis.opsForZSet().add(RedisKeys.bidwordInv(kw), member, bid);
            }
        }

        // 4. 派生库读向量文本，再写广告自有库；按已知(adId,itemId)复制，避免跨源JOIN。
        int embRows = 0;
        for (long[] ai : adItem) {
            List<SeedItemVectorMapper.Vector> vecs = derived.vectors(ai[1]);
            // MyBatis会把全NULL投影映射为null行；与原getString返回NULL一样跳过缺向量项。
            if (vecs.isEmpty() || vecs.get(0) == null || vecs.get(0).vector() == null) {
                continue;
            }
            embRows += adDb.insertVector(ai[0], vecs.get(0).vector(), vecs.get(0).model());
        }

        log.info("seed-ads 完成:广告主 {} / 广告 {} / 创意 {}(每广告 {} 套,DCO 用)/ 竞价词 {} / ad_embedding {} 条;"
                        + "广告表分片落 ds_0/ds_1,Redis 倒排已写。",
                numAdvertisers, m, (long) m * creativesPerAd, creativesPerAd, bidwordCount, embRows);
    }

    /** 生成 k 套创意标题变体(教学:同一电影标题加不同营销前缀)。第 0 套为原标题(默认创意)。 */
    private static String[] creativeTitles(String title, int k) {
        String base = title == null ? "" : title;
        String[] prefixes = {"", "🔥 ", "限时特惠 · ", "精选推荐 · ", "全新上线 · "};
        int n = Math.max(1, k);
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            out[i] = prefixes[i % prefixes.length] + base;
        }
        return out;
    }

    /** 从标题分词 + 类目 genre 名生成竞价词(去停用词/短词,去重,限量)。 */
    private static Set<String> keywords(String title, String category) {
        Set<String> kws = new LinkedHashSet<>();
        // 标题去掉结尾的年份 "(1995)",分词取实词
        String t = title == null ? "" : title.replaceAll("\\(\\d{4}\\)", " ").toLowerCase();
        for (String tok : t.split("[^a-z0-9]+")) {
            if (tok.length() >= 4 && kws.size() < 5) {
                kws.add(tok);
            }
        }
        // 类目 genre(MovieLens 以 | 分隔)
        if (category != null) {
            for (String g : category.split("\\|")) {
                String gg = g.trim().toLowerCase();
                if (gg.length() >= 3 && !gg.equals("(no genres listed)")) {
                    kws.add(gg);
                }
            }
        }
        return kws;
    }

    private void clear() {
        // #3:ad_event / ad_embedding 走 ad-serving 自有库(adDb)
        try { adDb.clearData(); }
        catch (RuntimeException failure) { throw new PublicErrorException(ErrorCode.JOB_SEED_CLEAR_FAILED, failure); }
        RuntimeException failure = null;
        // 分片库:advertiser/ad/bidword/ad_creative(ShardingSphere 把 TRUNCATE 下发到各分片;逐表避免依赖多表语法)
        for (SeedAdMapper.ClearTable t : SeedAdMapper.ClearTable.values()) {
            try {
                sharded.truncate(t);
            } catch (RuntimeException error) {
                // 保留逐表继续清理，但任何失败都阻止后续生成；部分效果不能当回滚。
                if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
            }
        }
        try {
            Set<String> keys = redis.keys(RedisKeys.bidwordInv("*"));
            if (keys == null) throw new PublicErrorException(ErrorCode.JOB_SEED_CLEAR_FAILED);
            if (!keys.isEmpty()) {
                if (redis.delete(keys) == null) throw new PublicErrorException(ErrorCode.JOB_SEED_CLEAR_FAILED);
            }
        } catch (RuntimeException error) {
            if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
        }
        if (failure != null) throw new PublicErrorException(ErrorCode.JOB_SEED_CLEAR_FAILED, failure);
        log.info("已清空广告相关表 + Redis 倒排");
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

}
