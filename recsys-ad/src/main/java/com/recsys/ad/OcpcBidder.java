package com.recsys.ad;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * oCPC 智能出价(docs/05 §6,M6)。把广告主的"目标转化成本(target_cpa)"自动换算成本次竞价的出价。
 *
 * <p><b>原理</b>:CPC 计费下广告主按点击付费,期望每次转化成本 = 出价 / pCVR。
 * 令 {@code bid = targetCpa × pCVR} 即可使期望转化成本 ≈ targetCpa。再乘一个离线学习的
 * 调节系数 {@code k}(反馈控制,纠正 pCVR 系统偏差并把实际 CPA 拉回目标):
 * <pre>bid = targetCpa × pCVR × k</pre>
 * 该出价随后照常进入 {@link BiddingService} 的 eCPM 排序与 GSP 计费(排序/计费逻辑不变,
 * oCPC 只动"出价怎么来")。
 *
 * <p><b>优雅降级</b>:oCPC 关闭 / 非 oCPC 广告 / 无 target_cpa → 返回 manual bid 原值;
 * 系数缺失 → k=1.0(等价于纯 targetCpa×pCVR,不做偏差校正);Redis 不可用 → 同样 k=1.0。
 */
@Service
public class OcpcBidder {

    private static final Logger log = LoggerFactory.getLogger(OcpcBidder.class);

    private final StringRedisTemplate redis;
    private final AdProperties props;

    public OcpcBidder(StringRedisTemplate redis, AdProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * 计算本次竞价的有效出价。
     *
     * @param optimizationType 广告优化方式(CPC / OCPC)
     * @param targetCpa        目标转化成本(元/转化);仅 OCPC 生效
     * @param manualBid        广告主手动出价(CPC 路 / oCPC 关闭时的回退)
     * @param advertiserId     广告主(取调节系数)
     * @param pcvr             排序模型预估转化率(mmoe/din 给出;否则 0 → 用先验)
     * @return 进入竞价的有效出价(元)
     */
    public double effectiveBid(String optimizationType, double targetCpa, double manualBid,
                               long advertiserId, double pcvr) {
        boolean ocpc = "OCPC".equalsIgnoreCase(optimizationType) && targetCpa > 0;
        if (!props.getOcpc().isEnabled() || !ocpc) {
            return manualBid;
        }
        double p = pcvr > 0 ? pcvr : props.getOcpc().getDefaultPcvr();
        double k = coefficient(advertiserId);
        double bid = targetCpa * p * k;
        double cap = props.getOcpc().getMaxBid();
        if (cap > 0) {
            bid = Math.min(bid, cap);
        }
        return Math.max(0.0, bid);
    }

    /** 读广告主 oCPC 调节系数 k(ad:ocpc:{adv});缺失/异常 → 1.0。 */
    public double coefficient(long advertiserId) {
        try {
            String s = redis.opsForValue().get(RedisKeys.adOcpc(advertiserId));
            if (s != null && !s.isBlank()) {
                double k = Double.parseDouble(s.trim());
                if (Double.isFinite(k) && k > 0) {
                    return k;
                }
            }
        } catch (Exception e) {
            log.debug("读 oCPC 系数失败,退 1.0: {}", e.getMessage());
        }
        return 1.0;
    }
}
