package com.recsys.common.ad;

/**
 * 广告计费模式(A1,docs/07)。决定两件事:
 * <ol>
 *   <li><b>eCPM 的"每单位出价价值因子"(billFactor)</b>——把不同计费单位的出价换算成可比的
 *       每次曝光期望收入,让 CPC/CPM/CPA 在同一场竞价里按同一把尺子排序、GSP 计费;</li>
 *   <li><b>在哪个事件扣费</b>({@link Unit})——CPC/OCPC 点击时扣、CPM/OCPM 曝光时扣、CPA 转化时扣。</li>
 * </ol>
 *
 * <p>billFactor 使 {@code eCPM = pacedBid × billFactor} 对所有类型成立,GSP 次价
 * {@code charged = nextAdRank / billFactor} 因而天然以"自身计费单位"计价(旧 CPC 逻辑是本式的特例)。
 * 具体 billFactor 的组装在 {@code BiddingService}(需要 pCTR/pCVR/quality/relevance)。
 */
public enum BidType {
    /** 按点击计费(手动出价)。 */
    CPC(Unit.CLICK),
    /** oCPC:按点击计费,出价由目标 CPA 自动换算(targetCpa×pCVR×k)。 */
    OCPC(Unit.CLICK),
    /** 按曝光计费(手动千次/单次出价)。 */
    CPM(Unit.IMPRESSION),
    /** oCPM:按曝光计费,转化优化(出价=目标 CPA,billFactor 含 pCTR×pCVR)。 */
    OCPM(Unit.IMPRESSION),
    /** 按转化计费(手动出价)。 */
    CPA(Unit.CONVERSION);

    /** 扣费事件。 */
    public enum Unit { CLICK, IMPRESSION, CONVERSION }

    private final Unit unit;

    BidType(Unit unit) {
        this.unit = unit;
    }

    public Unit unit() {
        return unit;
    }

    public boolean chargeOnClick() {
        return unit == Unit.CLICK;
    }

    public boolean chargeOnImpression() {
        return unit == Unit.IMPRESSION;
    }

    public boolean chargeOnConversion() {
        return unit == Unit.CONVERSION;
    }

    /** 解析 ad.optimization_type;未知/空 → 退 CPC(安全默认,与旧行为一致)。 */
    public static BidType from(String s) {
        if (s == null) {
            return CPC;
        }
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CPC;
        }
    }
}
