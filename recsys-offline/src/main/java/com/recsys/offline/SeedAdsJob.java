package com.recsys.offline;

import com.recsys.common.ad.BidwordInvCodec;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
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

    /** 主库:item/item_embedding 读(共享,留主库)。 */
    private final JdbcTemplate jdbc;
    /** #3:ad_event/ad_embedding 写走 adDbJdbc —— ad-serving 自有库(默认 recsys,AD_PG_DB 设则拆库)。 */
    private final JdbcTemplate adDb;
    /** 分片库:advertiser/ad/bidword/ad_creative 写(按分片键落 ds_0/ds_1)。 */
    private final JdbcTemplate sharded;
    private final StringRedisTemplate redis;

    public SeedAdsJob(JdbcTemplate jdbc, @Qualifier("adDbJdbc") JdbcTemplate adDb,
                      @Qualifier("adShardingJdbc") JdbcTemplate sharded,
                      StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.adDb = adDb;
        this.sharded = sharded;
        this.redis = redis;
    }

    @Override
    public String name() {
        return "seed-ads";
    }

    @Override
    public void run(ApplicationArguments args) {
        int numAds = intArg(args, "ads", 800);
        int numAdvertisers = intArg(args, "advertisers", 30);
        int creativesPerAd = intArg(args, "creatives-per-ad", 3);
        long seed = intArg(args, "seed", 42);
        if (args.containsOption("clear")) {
            clear();
        }
        Random rnd = new Random(seed);
        String it = ItemQuery.table(args);   // #3:item 读来源表(默认 item)

        // 取一批随机 item 作为广告创意(优先有向量的 item,让 SEMANTIC_AD 可用)
        List<long[]> items = new ArrayList<>(); // [itemId]
        List<String> titles = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        jdbc.query(
                "SELECT i.item_id, i.title, i.category, " +
                "       (e.item_id IS NOT NULL) AS has_vec " +
                "FROM " + it + " i LEFT JOIN item_embedding e ON e.item_id = i.item_id " +
                "ORDER BY has_vec DESC, random() LIMIT ?",
                rs -> {
                    items.add(new long[]{rs.getLong("item_id")});
                    titles.add(rs.getString("title"));
                    categories.add(rs.getString("category"));
                },
                numAds);
        if (items.isEmpty()) {
            log.warn("item 表为空,先跑 --job=import-items");
            return;
        }
        int m = items.size();

        // 1. 广告主:日预算对数均匀分布在 [50, 5000] 元(分片库:按 advertiser_id 落 ds_0/ds_1)
        for (int a = 1; a <= numAdvertisers; a++) {
            double budget = Math.round(50 * Math.pow(100, rnd.nextDouble())); // 50..5000
            sharded.update("INSERT INTO advertiser(advertiser_id,name,daily_budget,status) " +
                    "VALUES(?,?,?, 'active')", a, "advertiser-" + a, budget);
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
            sharded.update("INSERT INTO ad(ad_id,advertiser_id,item_id,title,landing_url,quality_score," +
                            "status,optimization_type,target_cpa) VALUES(?,?,?,?,?,?, 'active',?,?)",
                    adId, advertiserId, itemId, title, "https://example.com/ad/" + adId, quality,
                    optType, targetCpa);
            adItem.add(new long[]{adId, itemId});

            // DCO 创意(docs/05 §7 M7):每个广告 N 套创意(标题变体),多臂老虎机在线择优展示
            String[] variants = creativeTitles(title, creativesPerAd);
            for (int v = 0; v < variants.length; v++) {
                // creative_id 不传 → ShardingSphere Snowflake keygen 填充(seed 不需要回读该 id)
                sharded.update("INSERT INTO ad_creative(ad_id,title,landing_url,status) VALUES(?,?,?, 'active')",
                        adId, variants[v], "https://example.com/ad/" + adId + "?c=" + v);
            }

            Set<String> keywords = keywords(title, categories.get(i));
            for (String kw : keywords) {
                double bid = round2(clamp(Math.exp(rnd.nextGaussian() * 0.5), 0.2, 10.0)); // 对数正态≈1元
                String matchType = kw.contains(" ") ? "PHRASE" : "EXACT";
                long bidwordId = ++bidwordSeq;
                sharded.update(
                        "INSERT INTO bidword(id,ad_id,keyword,match_type,bid,bid_mode) " +
                        "VALUES(?,?,?,?,?, 'CPC')",
                        bidwordId, adId, kw, matchType, bid);
                bidwordCount++;
                // Redis 倒排:member 自包含,score=bid
                String member = BidwordInvCodec.encode(adId, bidwordId, itemId, advertiserId, quality);
                redis.opsForZSet().add(RedisKeys.bidwordInv(kw), member, bid);
            }
        }

        // 4. ad_embedding(主库单表 ds_0):按已知 (adId,itemId) 从 item_embedding 拷贝。
        //    不能再 JOIN 分片的 ad(跨数据源),用逐条 INSERT ... SELECT(ad_embedding 与 item_embedding 同在主库)。
        // #3 拆库:ad_embedding 与 item_embedding 可能不同库(AD_PG_DB),故从主库读向量(text 传输)→ 写 adDb。
        int embRows = 0;
        for (long[] ai : adItem) {
            List<String[]> vecs = jdbc.query(
                    "SELECT embedding::text AS v, model FROM item_embedding WHERE item_id = ?",
                    (rs, n) -> new String[]{rs.getString("v"), rs.getString("model")}, ai[1]);
            if (vecs.isEmpty() || vecs.get(0)[0] == null) {
                continue;
            }
            embRows += adDb.update(
                    "INSERT INTO ad_embedding(ad_id, embedding, model) VALUES(?, CAST(? AS vector), ?) " +
                    "ON CONFLICT (ad_id) DO NOTHING",
                    ai[0], vecs.get(0)[0], vecs.get(0)[1]);
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
        adDb.execute("TRUNCATE ad_event, ad_embedding");
        // 分片库:advertiser/ad/bidword/ad_creative(ShardingSphere 把 TRUNCATE 下发到各分片;逐表避免依赖多表语法)
        for (String t : new String[]{"bidword", "ad_creative", "ad", "advertiser"}) {
            try {
                sharded.execute("TRUNCATE " + t);
            } catch (Exception e) {
                log.warn("清空分片表 {} 失败(忽略): {}", t, e.getMessage());
            }
        }
        try {
            Set<String> keys = redis.keys("bidword:inv:*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            log.warn("清理 Redis 倒排失败(忽略): {}", e.getMessage());
        }
        log.info("已清空广告相关表 + Redis 倒排");
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }
}
