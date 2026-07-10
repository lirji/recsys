package com.recsys.query;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 词项 IDF 权重查表(R8)。离线 {@code IdfJob} 把 {@code IDF=ln((N+1)/(df+1))+1} 物化到 Redis
 * Hash {@code idf:terms},本组件按刷新间隔整表 HGETALL 到内存缓存,供 query 理解给
 * {@code TermWeight} 赋权 —— 稀有词权重更高,替代旧的恒 1.0。
 *
 * <p><b>优雅降级</b>(与本仓一贯主张一致):Redis 未注入 / 作业未跑 / 表空 / 查询异常 →
 * {@link #weight} 一律返回中性 1.0(等同接入前)。OOV/缺失词也退 1.0(保守,不放大生僻词/拼写噪声)。
 *
 * <p><b>在线/离线契约</b>:必须与离线 {@code IdfJob} 用同一分词({@link com.recsys.common.query.QueryTokens}),
 * 否则在线查表词形与离线统计 df 的词形对不上。
 */
@Component
public class IdfWeighter {

    private static final Logger log = LoggerFactory.getLogger(IdfWeighter.class);
    private static final double NEUTRAL = 1.0;

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final QueryProperties props;

    private volatile Map<String, Double> idf = Map.of();
    private volatile long loadedAtMs = 0;

    public IdfWeighter(ObjectProvider<StringRedisTemplate> redisProvider, QueryProperties props) {
        this.redisProvider = redisProvider;
        this.props = props;
    }

    /** 词项 IDF 权重;未启用 / 缺失 / OOV → 中性 1.0。 */
    public double weight(String term) {
        if (!props.getIdf().isEnabled()) {
            return NEUTRAL;
        }
        Double v = current().get(term);
        return v == null ? NEUTRAL : v;
    }

    /** 到期(refreshMs)重载 IDF 表;非到期直接返回内存缓存。 */
    private Map<String, Double> current() {
        long now = System.currentTimeMillis();
        if (now - loadedAtMs < props.getIdf().getRefreshMs() && loadedAtMs != 0) {
            return idf;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - loadedAtMs < props.getIdf().getRefreshMs() && loadedAtMs != 0) {
                return idf;
            }
            idf = load();
            loadedAtMs = System.currentTimeMillis();
            return idf;
        }
    }

    private Map<String, Double> load() {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return idf; // 保留旧值(通常空);无 Redis → 全退 1.0
        }
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(RedisKeys.IDF_TERMS);
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            Map<String, Double> m = new HashMap<>(raw.size() * 2);
            for (Map.Entry<Object, Object> e : raw.entrySet()) {
                try {
                    m.put(String.valueOf(e.getKey()), Double.parseDouble(String.valueOf(e.getValue())));
                } catch (RuntimeException ignore) {
                    // 单条脏数据跳过,不拖垮整表
                }
            }
            log.debug("IDF 表已加载 {} 个词项", m.size());
            return m;
        } catch (Exception e) {
            log.debug("加载 IDF 表失败,词项权重退 1.0: {}", e.getMessage());
            return idf; // 保留旧值
        }
    }
}
