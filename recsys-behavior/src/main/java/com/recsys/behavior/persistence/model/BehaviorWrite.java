package com.recsys.behavior.persistence.model;

import java.sql.Timestamp;

/** 已完成服务端归因和时间规范化的行为写入值；action存显式名称，保持原数据契约。 */
public record BehaviorWrite(long userId, long itemId, String action, double value, String scene,
                            String bucket, String exposureId, Timestamp timestamp) { }
