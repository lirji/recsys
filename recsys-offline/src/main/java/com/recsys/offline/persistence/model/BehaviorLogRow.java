package com.recsys.offline.persistence.model;

import java.sql.Timestamp;

/** 行为复制保留源ID、水位顺序、可空字段与PG微秒时间，不重新生成事件身份。 */
public record BehaviorLogRow(long id, Long userId, Long itemId, String action, Double value,
                             String scene, String bucket, Timestamp timestamp, Integer position, String exposureId) { }
