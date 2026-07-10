package com.recsys.recengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.bandit.BanditModelDto;
import com.recsys.common.bandit.LinUcbModel;
import com.recsys.common.constant.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BanditScorer} 在线打分单测(R7)——用 mock Redis 喂手工 {@code bandit:model} JSON,
 * 验证:JSON 反序列化(经 {@link BanditModelDto} + Jackson)、就绪判定、LinUCB 探索加成、
 * Thompson 采样随机性、以及缺模型的零降级。覆盖 recsys-common 单测里够不到的 Jackson 往返。
 */
class BanditScorerTest {

    private static final List<String> ORDER = List.of("f0", "f1");

    /** 造一个沿 f0 方向重度探索、reward=1 的模型,序列化成 bandit:model JSON。 */
    private String modelJson() throws Exception {
        LinUcbModel m = LinUcbModel.create(2, 1.0);
        for (int i = 0; i < 100; i++) {
            m.accumulate(new double[]{1, 0}, 1.0);   // f0 方向反复观测(reward=1)
        }
        return new ObjectMapper().writeValueAsString(BanditModelDto.from(ORDER, m));
    }

    private BanditScorer scorerReturning(String json) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(RedisKeys.BANDIT_MODEL)).thenReturn(json);
        return new BanditScorer(redis);
    }

    @Test
    void ready_whenValidModel() throws Exception {
        assertTrue(scorerReturning(modelJson()).isReady());
    }

    @Test
    void notReady_whenMissingOrGarbage() {
        assertFalse(scorerReturning(null).isReady(), "缺 key → 未就绪");
        assertFalse(scorerReturning("").isReady(), "空串 → 未就绪");
        assertFalse(scorerReturning("{not json").isReady(), "坏 JSON → 未就绪");
    }

    @Test
    void linucb_explorationBonusForNovelContext() throws Exception {
        BanditScorer.Session s = scorerReturning(modelJson()).forRequest("linucb", 1.0);
        // 新颖方向(f1,几乎没探索过)应有正的探索加成
        double novel = s.score(Map.of("f0", 0.0, "f1", 1.0));
        assertTrue(novel > 0, "未探索方向应得正探索加成: " + novel);
        // 全零上下文 → θ̂ᵀ0 + α√0 = 0
        assertEquals(0.0, s.score(Map.of("f0", 0.0, "f1", 0.0)), 1e-9);
        // 已探索方向 f0 的不确定性更小 → 其 bonus 分量 < 新颖方向的 bonus 分量
        // (用 α 很大放大 bonus、θ̂ 影响相对小来对比方向不确定性)
        BanditScorer.Session big = scorerReturning(modelJson()).forRequest("linucb", 100.0);
        double exploredBonus = big.score(Map.of("f0", 1.0, "f1", 0.0));
        double novelBonus = big.score(Map.of("f0", 0.0, "f1", 1.0));
        assertTrue(novelBonus > exploredBonus, "新颖方向探索加成应更大: " + novelBonus + " vs " + exploredBonus);
    }

    @Test
    void thompson_variesAcrossRequestsAndCentersOnMean() throws Exception {
        BanditScorer scorer = scorerReturning(modelJson());
        Map<String, Double> x = Map.of("f0", 1.0, "f1", 0.0);
        // LinUCB 均值(参照)= θ̂ᵀx(alpha=0 → 无 bonus)
        double mean = scorerReturning(modelJson()).forRequest("linucb", 0.0).score(x);
        double sum = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        int reps = 400;
        for (int i = 0; i < reps; i++) {
            double sc = scorer.forRequest("thompson", 1.0).score(x);
            sum += sc;
            min = Math.min(min, sc);
            max = Math.max(max, sc);
        }
        assertTrue(max - min > 1e-6, "Thompson 每请求采样应有随机波动");
        assertEquals(mean, sum / reps, 0.1, "Thompson 采样均值应≈θ̂ᵀx");
    }

    @Test
    void degradation_missingModelScoresZero() {
        BanditScorer.Session s = scorerReturning(null).forRequest("linucb", 1.0);
        assertEquals(0.0, s.score(Map.of("f0", 1.0, "f1", 1.0)), 1e-12, "缺模型 → 打分 0(不影响融合)");
    }
}
