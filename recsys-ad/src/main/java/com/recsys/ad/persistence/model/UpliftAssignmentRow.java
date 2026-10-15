package com.recsys.ad.persistence.model;

import java.sql.Timestamp;

/** 随机分配的持久化快照，使用原TIMESTAMP绑定语义保存采样窗口。 */
public record UpliftAssignmentRow(String assignmentId, String requestId, long userId, long adId,
                                  long advertiserId, long itemId, boolean treatment, double propensity,
                                  double pctr, double pcvr, double quality, double relevance, double bid,
                                  String bucket, String modelVersion, Timestamp assignedAt, Timestamp outcomeDueAt) { }
