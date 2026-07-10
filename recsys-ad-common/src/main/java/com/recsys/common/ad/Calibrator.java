package com.recsys.common.ad;

/**
 * pCTR 校准契约(recsys-ad 实现)。
 *
 * <p>广告与推荐的红线区别(docs/05 §9.3):pCTR 进计费公式,偏差 = 多收/少收钱,
 * 因此排序模型的原始概率必须经校准才能进 eCPM 与 GSP。离线用 {@code ad_event}
 * (预估 pctr vs 实际点击率)拟合保序回归(isotonic),参数写 Redis {@code ad:calib:{model}},
 * 在线查表线性插值。
 *
 * <p>降级:无拟合参数时退化为 identity(原样返回),并由上层标记"未校准、仅供排序"。
 */
public interface Calibrator {

    /**
     * @param rawProb 排序模型原始概率([0,1])
     * @param model   模型标识(对应离线拟合的校准表,如排序策略名)
     * @return 校准后概率([0,1]);无校准表时原样返回
     */
    double calibrate(double rawProb, String model);

    /** 该模型是否已有可用的校准表(用于上层判断是否可进计费)。 */
    boolean isCalibrated(String model);
}
