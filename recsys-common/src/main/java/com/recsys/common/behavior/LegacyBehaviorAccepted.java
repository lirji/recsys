package com.recsys.common.behavior;

import com.recsys.common.constant.ActionType;

/** 旧行为的接受快照；源行身份只去重投递，不给无键HTTP增加业务幂等。 */
public record LegacyBehaviorAccepted(String eventType, int schemaVersion, String sourceId,
        String behaviorId, String eventId, Origin origin, PriorPublication priorPublication,
        String capturedAt, Behavior behavior, String sourceTsLocal) {
    public static final String TYPE = "LegacyBehaviorAcceptedV1";
    public static final int VERSION = 1;
    public static final String LIVE_TOPIC = "behavior-accepted-v1";
    public static final String HISTORY_TOPIC = "behavior-history-recovery-v1";
    /** 来源仅描述捕获知识，不能推断流量类别或自然曝光资格。 */
    public enum Origin { BEHAVIOR_POST, LEGACY_ROW }
    /** 即使broker确认，也不能改写捕获时的发布知识。 */
    public enum PriorPublication { NOT_ATTEMPTED, UNKNOWN }
    /** 字符串ID避免大整数损失；历史ts为null，原无时区时间单独保存。 */
    public record Behavior(String userId, String itemId, ActionType action, double value,
                           String scene, String bucket, String exposureId, Long ts) { }
    /** 逻辑流由不可变来源决定，调用者不能传入任意topic。 */
    public String destination() { return origin == Origin.BEHAVIOR_POST ? LIVE_TOPIC : HISTORY_TOPIC; }
    /** 稳定源行身份不依赖进程、请求内容或当前策略。 */
    public static String identity(String sourceId, String behaviorId) { return sourceId + ":user_behavior:" + behaviorId; }
    /** 旧value-only入口无法解释身份/来源，必须在连接Kafka前拒绝所有新版保留流及隔离后缀。 */
    public static void requireLegacyTopic(String topic) {
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("旧行为topic不能为空");
        for (String reserved : java.util.List.of(LIVE_TOPIC, HISTORY_TOPIC, "natural-impression-accepted-v1")) {
            if (topic.equals(reserved) || topic.startsWith(reserved + ".")) {
                throw new IllegalArgumentException("旧行为入口不能消费版本化保留事件流");
            }
        }
    }
}
