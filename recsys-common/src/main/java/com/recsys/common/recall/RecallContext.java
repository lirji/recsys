package com.recsys.common.recall;

import java.util.List;
import java.util.Map;

/**
 * 召回上下文。承载一次召回所需的输入。
 *
 * @param userId          用户 ID
 * @param size            期望候选规模(各路配额之和的目标,实际可超后由排序裁剪)
 * @param scene           场景
 * @param enabledChannels 本次启用的召回路(空 = 全开);由分层 A/B 的 recall 层 / 冷启动覆盖决定
 * @param params          召回额外参数(如 SEMANTIC 路的 {@code query} 文本),不可为 null
 * @param explain         explain 收集器(可空 sink);非 null 时召回填去重前每路原始召回数,见 {@link RecallExplain}
 */
public record RecallContext(long userId,
                            int size,
                            String scene,
                            List<RecallChannel> enabledChannels,
                            Map<String, String> params,
                            RecallExplain explain) {

    public RecallContext {
        enabledChannels = enabledChannels == null ? List.of() : enabledChannels;
        params = params == null ? Map.of() : params;
    }

    /** 兼容旧调用:5 参(无 explain sink)。既有召回调用点(在线/离线 eval)全部走这里,零改动。 */
    public RecallContext(long userId, int size, String scene,
                         List<RecallChannel> enabledChannels, Map<String, String> params) {
        this(userId, size, scene, enabledChannels, params, null);
    }

    /** 兼容旧调用:全开召回、无额外参数、无 explain。 */
    public RecallContext(long userId, int size, String scene) {
        this(userId, size, scene, List.of(), Map.of(), null);
    }

    /** 该路是否在本次启用(enabledChannels 为空表示全开)。 */
    public boolean isEnabled(RecallChannel channel) {
        return enabledChannels.isEmpty() || enabledChannels.contains(channel);
    }
}
