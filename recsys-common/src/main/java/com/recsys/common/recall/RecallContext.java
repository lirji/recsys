package com.recsys.common.recall;

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
 * @param explain             explain 收集器(可空 sink);非 null 时召回填去重前每路原始召回数,见 {@link RecallExplain}
 * @param recentPositiveItems 预取的用户近期正反馈物品(<b>item_id 降序</b>,与各路原 SQL 同口径),<b>可空</b>:
 *                            {@code null} = 未预取(通道回退各自查库);非 null(含空表) = 已预取,通道直接消费其
 *                            前缀而不再查库。由 {@link RecallService} 每请求取一次下发,消除 I2I/SWING/GENERATIVE
 *                            三路对 {@code user_behavior} 的重复查询与连接扇出。
 */
public record RecallContext(long userId,
                            int size,
                            String scene,
                            List<RecallChannel> enabledChannels,
                            Map<String, String> params,
                            RecallExplain explain,
                            List<Long> recentPositiveItems) {

    public RecallContext {
        enabledChannels = enabledChannels == null ? List.of() : enabledChannels;
        params = params == null ? Map.of() : params;
        // recentPositiveItems 保留 null 语义(区分"未预取"与"预取到空"),故不归一化
    }

    /** 兼容旧调用:6 参(无预取行为)。 */
    public RecallContext(long userId, int size, String scene,
                         List<RecallChannel> enabledChannels, Map<String, String> params,
                         RecallExplain explain) {
        this(userId, size, scene, enabledChannels, params, explain, null);
    }

    /** 兼容旧调用:5 参(无 explain sink)。既有召回调用点(在线/离线 eval)全部走这里,零改动。 */
    public RecallContext(long userId, int size, String scene,
                         List<RecallChannel> enabledChannels, Map<String, String> params) {
        this(userId, size, scene, enabledChannels, params, null, null);
    }

    /** 兼容旧调用:全开召回、无额外参数、无 explain。 */
    public RecallContext(long userId, int size, String scene) {
        this(userId, size, scene, List.of(), Map.of(), null, null);
    }

    /** 该路是否在本次启用(enabledChannels 为空表示全开)。 */
    public boolean isEnabled(RecallChannel channel) {
        return enabledChannels.isEmpty() || enabledChannels.contains(channel);
    }

    /** 返回一个填入预取近期正反馈物品的副本(其余字段不变)。 */
    public RecallContext withRecentPositiveItems(List<Long> items) {
        return new RecallContext(userId, size, scene, enabledChannels, params, explain, items);
    }
}
