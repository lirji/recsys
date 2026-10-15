package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import com.recsys.offline.persistence.mapper.primary.HotScoreMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 作业 hot:从 user_behavior 聚合物品热度,写 Redis {@code recall:hot} ZSet(score=热度),
 * 让 HotRecaller 走 Redis 而非降级查库的静态 popularity。
 *
 * <p>热度 = 各行为加权求和:CLICK=1,LIKE=2,PLAY=1,RATING=value(评分越高权重越大)。
 * 参数:--recent-days(只统计近 N 天行为,默认 0=全量)、--topn(写入榜单条数,默认 1000)。
 */
@Component
public class HotJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(HotJob.class);

    private final HotScoreMapper mapper;
    private final StringRedisTemplate redis;

    /** 主源Mapper负责聚合，作业保留Redis榜单发布规则。 */
    public HotJob(HotScoreMapper mapper, StringRedisTemplate redis) {
        this.mapper = mapper;
        this.redis = redis;
    }

    /** 保持热榜重建命令。 */
    @Override
    public String name() {
        return "hot";
    }

    /** 空结果保留旧榜单，完整查询成功后才按原顺序发布。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        int recentDays = intArg(args, "recent-days", 0);
        int topN = intArg(args, "topn", 1000);
        Long maxTs = BehaviorQuery.maxTs(args);   // 严格 eval:只统计切分点前的热度
        var source = BehaviorQuery.source(args);

        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        mapper.scan(source, recentDays, maxTs, topN, context -> {
            var row = context.getResultObject();
            tuples.add(ZSetOperations.TypedTuple.of(String.valueOf(row.itemId()), row.hot()));
        });

        if (tuples.isEmpty()) {
            log.warn("无行为数据,recall:hot 未更新;先跑 --job=import-behavior");
            return;
        }
        // 沿用删旧key后整榜写入；这两条Redis命令之间仍存在原有窗口。
        redis.delete(RedisKeys.HOT_RECALL);
        redis.opsForZSet().add(RedisKeys.HOT_RECALL, tuples);
        log.info("hot 完成:recall:hot 写入 {} 条(recent-days={}, topn={})",
                tuples.size(), recentDays, topN);
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }
}
