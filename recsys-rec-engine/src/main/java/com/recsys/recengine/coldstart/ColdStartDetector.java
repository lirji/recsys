package com.recsys.recengine.coldstart;

import com.recsys.recengine.RecEngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 冷启动判定:用户**无个性化信号**时为冷。
 *
 * <p>判据 = 无 {@code user_embedding} 行(向量召回拿不到长期画像) **且**
 * {@code user_behavior} 计数 &lt; 阈值(i2i/u2u/swing 都喂不出种子)。两条轻量 count 查询;
 * 任一查询异常时保守判为非冷(走常规链路,由热门兜底)。
 */
@Component
@EnableConfigurationProperties(RecEngineProperties.class)
public class ColdStartDetector {

    private static final Logger log = LoggerFactory.getLogger(ColdStartDetector.class);

    private final JdbcTemplate jdbc;
    private final RecEngineProperties props;

    public ColdStartDetector(JdbcTemplate jdbc, RecEngineProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public boolean isCold(long userId) {
        try {
            // 只数"真实互动"(CLICK/LIKE/PLAY/RATING),排除 IMPRESSION——
            // 曝光是系统每次推荐都会写的,把它计入会让用户请求几次后就"假性脱冷";
            // 且 i2i/u2u/swing 的种子也只认这些正反馈,口径一致。
            Long behaviorCount = jdbc.queryForObject(
                    "SELECT count(*) FROM user_behavior WHERE user_id=? " +
                    "AND action IN ('CLICK','LIKE','PLAY','RATING')", Long.class, userId);
            if (behaviorCount != null && behaviorCount >= props.getColdStart().getMinBehaviors()) {
                return false;
            }
            Integer hasVec = jdbc.queryForObject(
                    "SELECT count(*) FROM user_embedding WHERE user_id=?", Integer.class, userId);
            boolean cold = hasVec == null || hasVec == 0;
            if (cold) {
                log.debug("用户 {} 判定为冷启动(behaviors={}, hasVector={})", userId, behaviorCount, hasVec);
            }
            return cold;
        } catch (Exception e) {
            log.warn("冷启动判定失败,按非冷处理 user={}: {}", userId, e.getMessage());
            return false;
        }
    }
}
