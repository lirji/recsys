package com.recsys.ad;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/** 广告主 server-to-server 独立 outcome 入口；eventId 主键使并发/重试幂等。 */
@Service
public class AdOutcomeService {
    private final JdbcTemplate jdbc;
    public AdOutcomeService(@Qualifier("adDbJdbc") JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean record(String eventId, long advertiserId, long userId,
                          String objective, double value, Instant occurredAt) {
        if (eventId == null || eventId.isBlank() || advertiserId <= 0 || userId <= 0
                || objective == null || objective.isBlank() || !Double.isFinite(value)) {
            throw new IllegalArgumentException("outcome 参数非法");
        }
        Instant time = occurredAt == null ? Instant.now() : occurredAt;
        return jdbc.update("INSERT INTO ad_conversion_fact(event_id,advertiser_id,user_id,objective," +
                        "conversion_value,occurred_at) VALUES(?,?,?,?,?,?) ON CONFLICT(event_id) DO NOTHING",
                eventId, advertiserId, userId, objective, value, Timestamp.from(time)) == 1;
    }
}
