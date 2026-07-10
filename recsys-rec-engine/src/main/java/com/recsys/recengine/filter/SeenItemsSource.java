package com.recsys.recengine.filter;

import java.util.Set;

/**
 * 已正反馈物品来源(P4):把"已看过滤"读的来源与实现解耦。
 *
 * <p>两种来源,由 {@code recsys.behavior.seen-source} 选择、可一键回滚:
 * <ul>
 *   <li>{@code db}(默认):{@link DbSeenItemsSource} 直读行为上下文的 {@code user_behavior} 表(今日行为)。</li>
 *   <li>{@code replica}:{@link ReplicaSeenItemsSource} 读 rec-engine 自有的 {@code seen:{user}} 读模型
 *       (由 {@link SeenItemsConsumer} 消费 {@code behavior-events} 构建)—— 在线不再跨上下文直读 user_behavior。</li>
 * </ul>
 */
public interface SeenItemsSource {

    /** 该用户正反馈(CLICK/LIKE/PLAY/RATING)过的物品 id 集合;失败返回空集(fail-open,不过滤)。 */
    Set<Long> seenItems(long userId);
}
