package com.recsys.ad;

import com.recsys.ad.persistence.mapper.addb.AdConversionFactMapper;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/** 广告主 server-to-server 独立 outcome 入口；eventId 主键使并发/重试幂等。 */
@Service
public class AdOutcomeService {
    private final AdConversionFactMapper mapper;
    /** 复用广告事件库的唯一键完成最终幂等判断。 */
    public AdOutcomeService(AdConversionFactMapper mapper) { this.mapper = mapper; }

    /** 校验通过后记录首次事实；重复事件不覆盖原始内容。 */
    public boolean record(String eventId, long advertiserId, long userId,
                          String objective, double value, Instant occurredAt) {
        if (eventId == null || eventId.isBlank() || advertiserId <= 0 || userId <= 0
                || objective == null || objective.isBlank() || !Double.isFinite(value)) {
            throw new IllegalArgumentException("outcome 参数非法");
        }
        Instant time = occurredAt == null ? Instant.now() : occurredAt;
        return mapper.insert(eventId, advertiserId, userId, objective, value, Timestamp.from(time)) == 1;
    }
}
