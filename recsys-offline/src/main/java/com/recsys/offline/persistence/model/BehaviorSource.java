package com.recsys.offline.persistence.model;

/** CLI来源仅允许既有owner表和离线读仓，XML不接受动态表名。 */
public enum BehaviorSource {
    USER_BEHAVIOR("user_behavior"), BEHAVIOR_LOG("behavior_log");

    private final String code;
    BehaviorSource(String code) { this.code=code; }
    /** 迁移期间兼容尚未退出的白名单来源参数，不用于新Mapper拼SQL。 */
    public String code() { return code; }
    /** 保持旧CLI未知来源的拒绝提示与默认值处理分工。 */
    public static BehaviorSource fromCode(String code) {
        for(var source:values()) if(source.code.equals(code)) return source;
        throw new IllegalArgumentException("非法 --behavior-table(仅 user_behavior / behavior_log): " + code);
    }
}
