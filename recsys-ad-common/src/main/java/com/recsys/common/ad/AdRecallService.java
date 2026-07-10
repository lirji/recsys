package com.recsys.common.ad;

import java.util.List;

/**
 * 广告召回契约(recsys-ad 实现)。query→ad 多路并行、合并去重。
 * 关键约束:每一路独立降级——某路(尤其依赖 embedding 的 SEMANTIC_AD)失败返回空,
 * 不应使整条广告链路崩溃;关键词路是兜底主力。
 */
public interface AdRecallService {

    /**
     * @param ctx 广告召回上下文(结构化 query + userId + 启用路)
     * @return 去重后的广告候选(主路按 {@link AdChannel#priority()} 取首位)
     */
    List<AdCandidate> recall(AdRecallContext ctx);
}
