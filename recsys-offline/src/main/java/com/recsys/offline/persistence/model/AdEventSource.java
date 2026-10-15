package com.recsys.offline.persistence.model;

/** 广告事件来源限定为原主表和离线读仓，XML只选择固定表名。 */
public enum AdEventSource {
    AD_EVENT("ad_event"), AD_EVENT_LOG("ad_event_log");

    private final String code;
    AdEventSource(String code) { this.code = code; }
    /** 来源的稳定CLI编码；Mapper接收枚举并由固定XML分支选择表。 */
    public String code() { return code; }
    /** 未知来源沿用原CLI错误提示，不能由输入拼接动态表名。 */
    public static AdEventSource fromCode(String code) {
        for (var source : values()) if (source.code.equals(code)) return source;
        throw new IllegalArgumentException("非法 --ad-event-table(仅 ad_event / ad_event_log): " + code);
    }
}
