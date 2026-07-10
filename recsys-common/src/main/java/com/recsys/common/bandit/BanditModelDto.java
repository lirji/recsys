package com.recsys.common.bandit;

import java.util.List;

/**
 * {@link LinUcbModel} 的可序列化载体(R7):离线 {@code bandit-stats} 写、在线 {@code BanditScorer} 读,
 * JSON 落在 Redis {@code bandit:model}。是一个纯 record —— 序列化由持有 Jackson 的 app 模块完成
 * (recsys-common 主编译期不引 Jackson),契约(字段/顺序)集中在此。
 *
 * @param order  上下文特征名顺序(= FeatureAssembler 的 dense_order,在线/离线按此从特征快照取 x,逐位一致的契约)
 * @param lambda 岭正则 λ(A 初始化为 λI)
 * @param n      累计观测样本数(审计/监控)
 * @param a      充分统计 A = λI + Σ x·xᵀ(d×d)
 * @param b      充分统计 b = Σ reward·x(d)
 */
public record BanditModelDto(List<String> order, double lambda, long n, double[][] a, double[] b) {

    /** 从纯数学模型 + 特征名顺序构造载体(离线写侧用)。 */
    public static BanditModelDto from(List<String> order, LinUcbModel model) {
        return new BanditModelDto(order, model.getLambda(), model.getN(), model.getA(), model.getB());
    }

    /** 还原纯数学模型(在线读侧 / 增量续训 warm-start 用)。 */
    public LinUcbModel toModel() {
        return LinUcbModel.of(lambda, a, b, n);
    }
}
