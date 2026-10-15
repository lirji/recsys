package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.recsys.offline.persistence.mapper.primary.ColdBanditMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 作业 cold-bandit-stats:物化冷启动类目 bandit 的统计到 Redis,供在线 {@code ColdStartBandit} 算 UCB。
 *
 * <p>按 {@code item.category} 聚合 {@code user_behavior}:
 * <ul>
 *   <li>impr(cat) = 该类目下所有行为数(曝光/试验次数的代理);</li>
 *   <li>pos(cat)  = 正反馈行为数(CLICK/LIKE/PLAY 或 RATING≥min-rating)。</li>
 * </ul>
 * 写 {@code cold:cat:{category}} = "impr,pos" 与 {@code cold:cat:total} = Σ impr。
 * 在线类目 UCB = pos/impr(exploit)+ coef·sqrt(ln(total+e)/(impr+1))(explore)。
 *
 * <p>参数:--min-rating(默认 4.0)。类比广告的 {@code ad-explore-stats}(那是按广告聚合)。
 */
@Component
public class ColdBanditStatsJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(ColdBanditStatsJob.class);

    private final ColdBanditMapper mapper;
    private final StringRedisTemplate redis;

    /** Mapper负责原双来源关联，作业继续按既有键协议发布类目统计。 */
    public ColdBanditStatsJob(ColdBanditMapper mapper, StringRedisTemplate redis) {
        this.mapper = mapper;
        this.redis = redis;
    }

    /** 保持冷启动类目统计命令。 */
    @Override
    public String name() {
        return "cold-bandit-stats";
    }

    /** 空结果不覆盖旧统计，消失类目的旧键保留原行为。 */
    @Override
    public void run(ApplicationArguments args) {
        var behavior = BehaviorQuery.source(args);
        var items = ItemQuery.source(args);
        double minRating = JobArguments.decimal(args, "min-rating", 4.0);

        List<ColdBanditMapper.Stats> rows = mapper.categoryStats(behavior, items, minRating);

        if (rows.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_NO_CATEGORY_STATS);
        }
        long total = 0;
        for (var row : rows) {
            redis.opsForValue().set(RedisKeys.coldCatStats(row.category()), row.impressions() + "," + row.positives());
            total += row.impressions();
        }
        redis.opsForValue().set(RedisKeys.COLD_CAT_TOTAL, String.valueOf(total));
        log.info("cold-bandit-stats 完成:{} 个类目,总曝光 {};示例 {} → impr={},pos={}",
                rows.size(), total, rows.get(0).category(), rows.get(0).impressions(), rows.get(0).positives());
    }

}
