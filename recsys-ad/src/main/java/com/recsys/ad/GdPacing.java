package com.recsys.ad;

/**
 * 品牌广告 / GD 保量的投放进度分配纯函数(A4)——无副作用,便于单测({@code GdPacingTest})。
 *
 * <p>合约在 [start, end] 内保证交付 guaranteed 次曝光。按<b>时间线性 pacing</b>:到时刻 t 的期望已交付量
 * {@code expected = guaranteed · clamp((t-start)/(end-start), 0, 1)}。
 * <b>落后紧迫度</b> {@code urgency = (expected − delivered) / expected}:>0=落后(该保量、竞价让位)、
 * ≤0=不落后(让位给竞价广告)。选紧迫度最高的合约优先出,既保量又不过度挤占竞价收入。
 */
final class GdPacing {

    private GdPacing() {
    }

    /** 到 nowMs 时按线性 pacing 的期望已交付量。guaranteed≤0 或窗口非法 → 0。 */
    static double expectedDelivered(long guaranteed, long startMs, long endMs, long nowMs) {
        if (guaranteed <= 0 || endMs <= startMs) {
            return 0.0;
        }
        double frac = (double) (nowMs - startMs) / (endMs - startMs);
        frac = Math.max(0.0, Math.min(1.0, frac));
        return guaranteed * frac;
    }

    /**
     * 落后紧迫度:>0 表示落后于时间进度(应保量),数值=落后占期望的比例;
     * 已交付满 / 未到窗口 / 超前 → ≤0(不保量,让位竞价)。
     */
    static double urgency(long delivered, long guaranteed, long startMs, long endMs, long nowMs) {
        if (delivered >= guaranteed) {
            return -1.0;   // 已交付满,合约完成
        }
        double expected = expectedDelivered(guaranteed, startMs, endMs, nowMs);
        if (expected <= 0) {
            return -1.0;   // 还没到投放期 / 无期望
        }
        return (expected - delivered) / expected;
    }

    /** 是否落后到需要保量:紧迫度 > tolerance(容忍带,避免临界抖动)。 */
    static boolean behind(long delivered, long guaranteed, long startMs, long endMs, long nowMs, double tolerance) {
        return urgency(delivered, guaranteed, startMs, endMs, nowMs) > tolerance;
    }
}
