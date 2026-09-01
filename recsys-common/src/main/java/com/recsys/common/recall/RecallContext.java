package com.recsys.common.recall;

import com.recsys.common.query.TermWeight;

import java.util.List;
import java.util.Map;

/**
 * 召回上下文。承载一次召回所需的输入。
 *
 * @param userId              用户 ID
 * @param size                期望候选规模(各路配额之和的目标,实际可超后由排序裁剪)
 * @param scene               场景
 * @param enabledChannels     本次启用的召回路(空 = 全开);由分层 A/B 的 recall 层 / 冷启动覆盖决定
 * @param params              召回额外参数(如 SEMANTIC 路的 {@code query} 文本),不可为 null
 * @param queryTerms          query 理解产出的词项 + IDF 权重(R8-FTS);普通推荐/离线评估为空
 * @param explain             explain 收集器(可空 sink);非 null 时召回填去重前每路原始召回数,见 {@link RecallExplain}
 * @param recentPositiveItems 预取的用户近期正反馈物品(<b>item_id 降序</b>,与各路原 SQL 同口径),<b>可空</b>:
 *                            {@code null} = 未预取(通道回退各自查库);非 null(含空表) = 已预取,通道直接消费其
 *                            前缀而不再查库。由 {@link RecallService} 每请求取一次下发,消除 I2I/SWING/GENERATIVE
 *                            三路对 {@code user_behavior} 的重复查询与连接扇出。
 * @param behaviorSequence    真正按事件时间升序(oldest→newest)的正反馈序列，供 MIND 多兴趣模型消费；
 *                            {@code null}=未预取，空表=已预取但冷用户。与 recentPositiveItems 分开，避免把
 *                            旧通道按 item_id 排序的种子误当时间序列。
 */
public record RecallContext(long userId,
                            int size,
                            String scene,
                            List<RecallChannel> enabledChannels,
                            Map<String, String> params,
                            List<TermWeight> queryTerms,
                            RecallExplain explain,
                            List<Long> recentPositiveItems,
                            List<Long> behaviorSequence) {

    public RecallContext {
        enabledChannels = enabledChannels == null ? List.of() : enabledChannels;
        params = params == null ? Map.of() : params;
        queryTerms = queryTerms == null ? List.of() : List.copyOf(queryTerms);
        // recentPositiveItems 保留 null 语义(区分"未预取"与"预取到空"),故不归一化
        // behaviorSequence 同样保留 null 语义。
    }

    /** 兼容 R8 之后的 8 参调用。 */
    public RecallContext(long userId, int size, String scene,
                         List<RecallChannel> enabledChannels, Map<String, String> params,
                         List<TermWeight> queryTerms, RecallExplain explain,
                         List<Long> recentPositiveItems) {
        this(userId, size, scene, enabledChannels, params, queryTerms, explain, recentPositiveItems, null);
    }

    /** 兼容旧调用:7 参(无 query 词项)。 */
    public RecallContext(long userId, int size, String scene,
                         List<RecallChannel> enabledChannels, Map<String, String> params,
                         RecallExplain explain, List<Long> recentPositiveItems) {
        this(userId, size, scene, enabledChannels, params, List.of(), explain, recentPositiveItems, null);
    }

    /** 兼容旧调用:6 参(无预取行为)。 */
    public RecallContext(long userId, int size, String scene,
                         List<RecallChannel> enabledChannels, Map<String, String> params,
                         RecallExplain explain) {
        this(userId, size, scene, enabledChannels, params, List.of(), explain, null, null);
    }

    /** 兼容旧调用:5 参(无 explain sink)。既有召回调用点(在线/离线 eval)全部走这里,零改动。 */
    public RecallContext(long userId, int size, String scene,
                         List<RecallChannel> enabledChannels, Map<String, String> params) {
        this(userId, size, scene, enabledChannels, params, List.of(), null, null, null);
    }

    /** 兼容旧调用:全开召回、无额外参数、无 explain。 */
    public RecallContext(long userId, int size, String scene) {
        this(userId, size, scene, List.of(), Map.of(), List.of(), null, null, null);
    }

    /** 该路是否在本次启用(enabledChannels 为空表示全开)。 */
    public boolean isEnabled(RecallChannel channel) {
        return enabledChannels.isEmpty() || enabledChannels.contains(channel);
    }

    /** 返回一个填入预取近期正反馈物品的副本(其余字段不变)。 */
    public RecallContext withRecentPositiveItems(List<Long> items) {
        return new RecallContext(userId, size, scene, enabledChannels, params, queryTerms, explain, items,
                behaviorSequence);
    }

    /** 返回一个填入真实时间序列的副本(其余字段不变)。 */
    public RecallContext withBehaviorSequence(List<Long> items) {
        return new RecallContext(userId, size, scene, enabledChannels, params, queryTerms, explain,
                recentPositiveItems, items == null ? List.of() : List.copyOf(items));
    }
}
