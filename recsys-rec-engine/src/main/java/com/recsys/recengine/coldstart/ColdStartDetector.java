package com.recsys.recengine.coldstart;

import com.recsys.recengine.RecEngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.recsys.recengine.persistence.mapper.primary.EngineBehaviorMapper;
import com.recsys.recengine.persistence.mapper.derived.EngineEmbeddingMapper;
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

    private final EngineBehaviorMapper behaviors;
    private final EngineEmbeddingMapper embeddings;
    private final RecEngineProperties props;

    /** 两个Mapper由精确扫描绑定各自原数据源，不依赖Primary猜测。 */
    public ColdStartDetector(EngineBehaviorMapper behaviors, EngineEmbeddingMapper embeddings, RecEngineProperties props) {
        this.behaviors = behaviors;
        this.embeddings = embeddings;
        this.props = props;
    }

    /** 先检查正反馈阈值，再检查派生向量行；任一失败按非冷降级。 */
    public boolean isCold(long userId) {
        try {
            // 只数"真实互动"(CLICK/LIKE/PLAY/RATING),排除 IMPRESSION——
            // 曝光是系统每次推荐都会写的,把它计入会让用户请求几次后就"假性脱冷";
            // 且 i2i/u2u/swing 的种子也只认这些正反馈,口径一致。
            long behaviorCount = behaviors.positiveBehaviorCount(userId);
            if (behaviorCount >= props.getColdStart().getMinBehaviors()) {
                return false;
            }
            int hasVec = embeddings.userEmbeddingCount(userId);
            boolean cold = hasVec == 0;
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
