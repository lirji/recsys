package com.recsys.recengine.content;

import com.recsys.content.Item;

import java.util.List;
import java.util.Map;

/**
 * 内容服务网关(P2,rec-engine 侧调用抽象)。屏蔽"进程内直调 {@code ContentService} vs 跨进程 gRPC 调
 * recsys-content-service"两种实现,供绞杀者迁移以 {@code recsys.content.serving.mode} 一键切换与回滚。
 *
 * <p>仅承载展示阶段对 top-N 的<b>批量 hydrate</b>(每请求 O(1) 次粗调用);排序/召回的逐候选类目读仍走进程内 lib。
 */
public interface ContentGateway {

    /** 批量取物品详情(id→Item;缺失的 id 不在 map 中),供展示 hydrate。 */
    Map<Long, Item> findByIds(List<Long> itemIds);
}
