package com.recsys.recengine.filter;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 已交互过滤:取用户**正反馈过**的物品集合,供编排层从召回结果里剔除"已看过"的。
 *
 * <p>口径:只算真实互动 {@code CLICK/LIKE/PLAY/RATING},**排除 IMPRESSION**(曝光是系统每次推荐都写的,
 * 当"已看"过滤会把被推物品永久屏蔽、几次请求后掏空召回池)。只用于**在线编排**(离线 eval 自行按时间切分)。
 *
 * <p><b>P4</b>:读的来源经 {@link SeenItemsSource} 解耦——{@code db}(直读 user_behavior,默认)或
 * {@code replica}(读 {@code seen:{user}} 事件读模型),{@code recsys.behavior.seen-source} 一键切换/回滚。
 * fail-open 在各 source 内实现(异常→空集,不过滤)。
 */
@Component
public class SeenItemsFilter {

    private final SeenItemsSource source;

    public SeenItemsFilter(SeenItemsSource source) {
        this.source = source;
    }

    /** 返回该用户正反馈过的物品 id 集合;异常时返回空集(不过滤)。 */
    public Set<Long> seenItems(long userId) {
        return source.seenItems(userId);
    }
}
