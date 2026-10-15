package com.recsys.offline.persistence.model;

/** 广告事件来源限定为原主表和离线读仓，XML只选择固定表名。 */
public enum AdEventSource {
    AD_EVENT("ad_event"), AD_EVENT_LOG("ad_event_log");

    private final String code;
    AdEventSource(String code) { this.code = code; }
    /** 为尚未迁移的作业保留既有白名单表名入口，新Mapper只接收枚举。 */
    public String code() { return code; }
    /** 未知来源沿用原CLI错误提示，不能由输入拼接动态表名。 */
    public static AdEventSource fromCode(String code) {
        for (var source : values()) if (source.code.equals(code)) return source;
        throw new IllegalArgumentException("非法 --ad-event-table(仅 ad_event / ad_event_log): " + code);
    }
}
