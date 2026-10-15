package com.recsys.offline.persistence.model;

import java.sql.Timestamp;

/** 广告事件复制的16个原字段，保留可空值、Long身份及原事件浮点精度。 */
public record AdEventLogRow(long id, String requestId, String query, Long userId,
                            Long adId, Long bidwordId, Integer position, String eventType,
                            Double pctr, Double pctrCalib, Double ecpm, Double chargedPrice,
                            Double relevance, String adBucket, Timestamp timestamp, Long creativeId) { }
