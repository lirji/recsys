package com.recsys.ad;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        return effectiveBid(optimizationType, targetCpa, manualBid, pcvr, coefficient(advertiserId));
    }

    /**
     * 有效出价的重载:接受<b>预取好</b>的调节系数 k,不再回库读 Redis。
     * 供 {@link BiddingService} 竞价循环用批量预取的 k 计算,避免每候选一次 Redis 往返。
     */
    public double effectiveBid(String optimizationType, double targetCpa, double manualBid,
                               double pcvr, double coefficient) {
        boolean ocpc = "OCPC".equalsIgnoreCase(optimizationType) && targetCpa > 0;
        if (!props.getOcpc().isEnabled() || !ocpc) {
            return manualBid;
        }
        double p = pcvr > 0 ? pcvr : props.getOcpc().getDefaultPcvr();
        double bid = targetCpa * p * coefficient;
        double cap = props.getOcpc().getMaxBid();
        if (cap > 0) {
            bid = Math.min(bid, cap);
        }
        return Math.max(0.0, bid);
    }

    /** 读广告主 oCPC 调节系数 k(ad:ocpc:{adv});缺失/异常 → 1.0。 */
    public double coefficient(long advertiserId) {
        try {
            return parseCoef(redis.opsForValue().get(RedisKeys.adOcpc(advertiserId)));
        } catch (Exception e) {
            log.debug("读 oCPC 系数失败,退 1.0: {}", e.getMessage());
            return 1.0;
        }
    }

    /**
     * 批量取 oCPC 调节系数(一次 MGET),供竞价循环预取避免每候选一次 Redis 往返。
     * 返回的 Map 只含"命中且有效(&gt;0)"的广告主;缺失/异常 → 不入 Map,调用方 getOrDefault(adv, 1.0)。
     */
    public Map<Long, Double> coefficients(Collection<Long> advertiserIds) {
        if (advertiserIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = advertiserIds.stream().distinct().collect(Collectors.toList());
        try {
            List<String> keys = ids.stream().map(RedisKeys::adOcpc).collect(Collectors.toList());
            List<String> vals = redis.opsForValue().multiGet(keys);
            Map<Long, Double> out = new HashMap<>();
            if (vals != null) {
                for (int i = 0; i < ids.size() && i < vals.size(); i++) {
                    double k = parseCoef(vals.get(i));
                    if (k != 1.0) {   // 只有非默认系数才入 Map;1.0 与缺失等价,交给 getOrDefault
                        out.put(ids.get(i), k);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("批量读 oCPC 系数失败,退 1.0: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 解析 oCPC 系数字符串:命中且有限且&gt;0 → 用它;否则 1.0。 */
    private static double parseCoef(String s) {
        if (s != null && !s.isBlank()) {
            try {
                double k = Double.parseDouble(s.trim());
                if (Double.isFinite(k) && k > 0) {
                    return k;
                }
            } catch (NumberFormatException ignored) {
                // 非数字 → 退 1.0
            }
        }
        return 1.0;
    }
}
