package com.recsys.offline;

import com.recsys.common.ad.BidwordInvCodec;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public SeedAdsJob(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
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
        long seed = intArg(args, "seed", 42);
        if (args.containsOption("clear")) {
            clear();
        }
        Random rnd = new Random(seed);

        // 取一批随机 item 作为广告创意(优先有向量的 item,让 SEMANTIC_AD 可用)
        List<long[]> items = new ArrayList<>(); // [itemId]
        List<String> titles = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        jdbc.query(
                "SELECT i.item_id, i.title, i.category, " +
                "       (e.item_id IS NOT NULL) AS has_vec " +
                "FROM item i LEFT JOIN item_embedding e ON e.item_id = i.item_id " +
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

        // 1. 广告主:日预算对数均匀分布在 [50, 5000] 元
        for (int a = 1; a <= numAdvertisers; a++) {
            double budget = Math.round(50 * Math.pow(100, rnd.nextDouble())); // 50..5000
            jdbc.update("INSERT INTO advertiser(advertiser_id,name,daily_budget,status) " +
                    "VALUES(?,?,?, 'active')", a, "advertiser-" + a, budget);
        }

        // 2/3. 广告 + 竞价词 + Redis 倒排
        int bidwordCount = 0;
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
            jdbc.update("INSERT INTO ad(ad_id,advertiser_id,item_id,title,landing_url,quality_score," +
                            "status,optimization_type,target_cpa) VALUES(?,?,?,?,?,?, 'active',?,?)",
                    adId, advertiserId, itemId, title, "https://example.com/ad/" + adId, quality,
                    optType, targetCpa);

            Set<String> keywords = keywords(title, categories.get(i));
            for (String kw : keywords) {
                double bid = round2(clamp(Math.exp(rnd.nextGaussian() * 0.5), 0.2, 10.0)); // 对数正态≈1元
                String matchType = kw.contains(" ") ? "PHRASE" : "EXACT";
                Long bidwordId = jdbc.queryForObject(
                        "INSERT INTO bidword(ad_id,keyword,match_type,bid,bid_mode) " +
                        "VALUES(?,?,?,?, 'CPC') RETURNING id",
                        Long.class, adId, kw, matchType, bid);
                bidwordCount++;
                // Redis 倒排:member 自包含,score=bid
                String member = BidwordInvCodec.encode(adId, bidwordId, itemId, advertiserId, quality);
                redis.opsForZSet().add(RedisKeys.bidwordInv(kw), member, bid);
            }
        }

        // 4. ad_embedding:从 item_embedding 拷贝(仅关联 item 有向量的广告)
        int embRows = jdbc.update(
                "INSERT INTO ad_embedding(ad_id, embedding, model) " +
                "SELECT a.ad_id, e.embedding, e.model FROM ad a " +
                "JOIN item_embedding e ON e.item_id = a.item_id " +
                "ON CONFLICT (ad_id) DO NOTHING");

        log.info("seed-ads 完成:广告主 {} / 广告 {} / 竞价词 {} / ad_embedding {} 条;Redis 倒排已写。",
                numAdvertisers, m, bidwordCount, embRows);
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
        jdbc.execute("TRUNCATE ad_event, ad_embedding, bidword, ad, advertiser RESTART IDENTITY CASCADE");
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
