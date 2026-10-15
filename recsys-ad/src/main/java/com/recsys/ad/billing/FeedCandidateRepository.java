package com.recsys.ad.billing;

import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.ad.delivery.SandboxDelivery.Candidate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** 广告 owner 的只读频控；不把旧响应计数或未接受预占误当曝光。 */
public class FeedCandidateRepository {
    public static final String MIGRATION = "db/ad-billing/V007__feed_frequency.sql";
    private final JdbcTemplate jdbc;
    public FeedCandidateRepository(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(5);
    }
    /** 启动验证新增索引和摘要，不自动改动正在使用的账务库。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(jdbc.queryForObject(
                "SELECT script_sha256 FROM ad_billing_schema_history WHERE version=7", String.class))) {
            throw new IllegalStateException("feed 频控迁移缺失或摘要漂移");
        }
        for (String name : List.of("ad_ledger_user_ad_time_idx", "ad_ledger_user_advertiser_time_idx")) {
            if (jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, name) == null) {
                throw new IllegalStateException("feed 频控索引缺失");
            }
        }
    }
    /** 一次有界查询；每个计数在阈值处停止，接受时间的上海日与原预算日明确分离。 */
    public Set<Long> eligible(long userId, List<Candidate> candidates, int perAd, int perAdvertiser) {
        if (userId <= 0 || candidates.size() > SandboxDelivery.MAX_SLOTS || perAd < 1 || perAdvertiser < 1) {
            throw new IllegalArgumentException("feed 频控查询范围非法");
        }
        if (candidates.isEmpty()) return Set.of();
        var parameters = new ArrayList<Object>();
        for (var c : candidates) { parameters.add(c.ad().adId()); parameters.add(c.ad().advertiserId()); }
        // 仅生成最多十组绑定占位符；没有将请求值拼接为 SQL 标识或字面量。
        String targets = String.join(",", Collections.nCopies(candidates.size(), "(?::bigint,?::bigint)"));
        String sql = """
                WITH target(ad_id,advertiser_id) AS (VALUES %s),
                bounds AS (SELECT date_trunc('day',statement_timestamp() AT TIME ZONE 'Asia/Shanghai')
                    AT TIME ZONE 'Asia/Shanghai' AS start_at)
                SELECT target.ad_id FROM target,bounds
                WHERE (SELECT count(*) FROM (SELECT 1 FROM ad_billing_ledger l
                    WHERE l.traffic_class='SANDBOX' AND l.user_id=? AND l.ad_id=target.ad_id
                      AND l.accepted_at>=bounds.start_at AND l.accepted_at<((bounds.start_at AT TIME ZONE 'Asia/Shanghai')+interval '1 day') AT TIME ZONE 'Asia/Shanghai'
                    LIMIT ?) hits) < ?
                  AND (SELECT count(*) FROM (SELECT 1 FROM ad_billing_ledger l
                    WHERE l.traffic_class='SANDBOX' AND l.user_id=? AND l.advertiser_id=target.advertiser_id
                      AND l.accepted_at>=bounds.start_at AND l.accepted_at<((bounds.start_at AT TIME ZONE 'Asia/Shanghai')+interval '1 day') AT TIME ZONE 'Asia/Shanghai'
                    LIMIT ?) hits) < ?
                """.formatted(targets);
        parameters.addAll(List.of(userId, perAd, perAd, userId, perAdvertiser, perAdvertiser));
        return Set.copyOf(jdbc.queryForList(sql, Long.class, parameters.toArray()));
    }
}
