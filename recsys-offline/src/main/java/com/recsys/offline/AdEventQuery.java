package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import org.springframework.boot.ApplicationArguments;

import com.recsys.offline.persistence.model.AdEventSource;

/**
 * ad_event 读的来源表切换(#3 ad-serving 上下文物理拆库,离线侧)。镜像 {@link BehaviorQuery#source}/{@link ItemQuery#source}:
 * 默认 {@code ad_event}(直读 ad-serving 上下文,回滚落点);{@code ad_event_log} = 读数据平台<b>自有</b>读仓
 * (DB-per-service,由 {@code sync-ad-event-log} 从 ad-serving 库同步)。仅允许两个既有来源；新Mapper通过枚举选择固定XML分支。
 *
 * <p>用法:新分析作业在 {@code run} 起始解析来源枚举，以局部变量传递给Mapper。写作业
 * (sim-ad-events/seed-ads)不用本类,改由 {@code adDbSqlSessionTemplate}(AD_PG_DB)写 ad-serving 自有库。
 */
final class AdEventQuery {

    private AdEventQuery() {
    }

    /** 单次执行只解析一次来源，避免可变表名字段跨调用残留。 */
    static AdEventSource source(ApplicationArguments args) {
        String value = JobArguments.text(args, "ad-event-table", "ad_event");
        try {
            return AdEventSource.fromCode(value);
        } catch (IllegalArgumentException invalid) {
            throw new PublicErrorException(ErrorCode.JOB_AD_EVENT_SOURCE_INVALID, invalid);
        }
    }
}
