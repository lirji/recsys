package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.AdChannel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 相关性门槛(docs/05 §4.3)——广告独有的硬约束:不相关的广告宁可不出(空位也比恶心用户强)。
 * 放在召回后、竞价前。
 *
 * <p>相关性 ∈ [0,1] 由主召回路与召回分推断:
 * <ul>
 *   <li>KW_EXACT:词项精确命中竞价词 = 强相关(kwMatch=1.0);</li>
 *   <li>KW_BROAD:改写/同义命中 = 次强(kwMatch=0.7);</li>
 *   <li>SEMANTIC_AD:query↔ad 余弦(召回分本身)= 语义相关;</li>
 *   <li>U2A:用户长期向量↔ad 余弦(召回分本身)= 个性化定向相关(query 无关,以用户兴趣为相关性依据);</li>
 *   <li>HOT_AD:兜底填充路,豁免门槛(给相关性下限,保填充率)。</li>
 * </ul>
 * relevance = kwWeight·kwMatch + semWeight·cosine,低于阈值丢弃。
 */
@Component
public class RelevanceGate {

    private final AdProperties props;

    public RelevanceGate(AdProperties props) {
        this.props = props;
    }

    /** 计算 query↔ad 相关性([0,1])。纯函数,编排层过滤与组装 SponsoredAd 时各调一次。 */
    public double relevance(AdCandidate c) {
        AdProperties.Recall r = props.getRecall();
        if (c.channel() == AdChannel.HOT_AD) {
            return r.getRelevanceThreshold(); // 兜底路豁免,刚好过线
        }
        double kwMatch = switch (c.channel()) {
            case KW_EXACT -> 1.0;
            case KW_BROAD -> 0.7;
            default -> 0.0;
        };
        double cosine = (c.channel() == AdChannel.SEMANTIC_AD || c.channel() == AdChannel.U2A)
                ? Math.max(0.0, Math.min(1.0, c.recallScore())) : 0.0;
        double rel = r.getKwWeight() * kwMatch + r.getSemWeight() * cosine;
        return Math.max(0.0, Math.min(1.0, rel));
    }

    /** 过滤:剔除相关性低于阈值的候选。 */
    public List<AdCandidate> filter(List<AdCandidate> candidates) {
        double threshold = props.getRecall().getRelevanceThreshold();
        List<AdCandidate> kept = new ArrayList<>(candidates.size());
        for (AdCandidate c : candidates) {
            if (relevance(c) >= threshold) {
                kept.add(c);
            }
        }
        return kept;
    }
}
