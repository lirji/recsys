package com.recsys.ad.billing;

import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.ad.delivery.SandboxDelivery.Candidate;
import com.recsys.ad.persistence.mapper.addb.FeedCandidateMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;
import java.util.*;

/** 广告 owner 的只读频控；不把旧响应计数或未接受预占误当曝光。 */
public class FeedCandidateRepository {
    public static final String MIGRATION = "db/ad-billing/V007__feed_frequency.sql";
    private final FeedCandidateMapper mapper;
    /** 复用adDb实际会话，不新建连接池；XML逐语句保留5秒超时。 */
    public FeedCandidateRepository(SqlSessionTemplate sessions) {
        this.mapper=sessions.getMapper(FeedCandidateMapper.class);
    }
    /** 启动验证新增索引和摘要，不自动改动正在使用的账务库。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes()))) {
            throw new IllegalStateException("feed 频控迁移缺失或摘要漂移");
        }
        for (String name : List.of("ad_ledger_user_ad_time_idx", "ad_ledger_user_advertiser_time_idx")) {
            if (DataAccessUtils.requiredSingleResult(mapper.indexNames(name)) == null) {
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
        var targets=candidates.stream().map(c->new FeedCandidateMapper.Target(c.ad().adId(),c.ad().advertiserId())).toList();
        return Set.copyOf(mapper.eligible(userId,targets,perAd,perAdvertiser));
    }
}
