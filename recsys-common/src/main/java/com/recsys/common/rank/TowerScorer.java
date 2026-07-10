package com.recsys.common.rank;

import java.util.List;
import java.util.Map;

/**
 * 双塔粗排打分契约(R6)——给定用户与候选物品集,返回"用户塔向量 · 物品塔向量"的学习型相似分。
 *
 * <p>让粗排({@code PreRankService})从"线性拍脑袋 3 特征"升级为复用已训双塔向量的<b>轻量学习型打分</b>,
 * 而无需引入新模型。实现在 {@code recsys-recall}(那里有 user 塔 ONNX + {@code item_tower_embedding}),
 * 经 {@code recsys-common} 接口被 {@code recsys-rank} 的 PreRankService 可选注入(避免 rank→recall 反向依赖)。
 *
 * <p>优雅降级:模型未就绪 / 无向量的候选在返回 map 中缺席,粗排对缺席项回退线性特征打分。
 */
public interface TowerScorer {

    /** 双塔 user 塔是否加载成功;false 时粗排走纯线性。 */
    boolean isReady();

    /**
     * 对候选集打双塔点积(余弦)分:itemId → 相似分。无 item 塔向量的候选可缺席(由上层回退处理)。
     * 未就绪 / 异常返回空 map(不抛)。
     */
    Map<Long, Double> score(long userId, List<Long> itemIds);
}
