package com.recsys.offline.persistence.model;

import java.util.Objects;

/** 只表达原查询条件：可空秒级上界不包含其后的微秒，不增加新评分约束。 */
public record PositiveFeedbackFilter(BehaviorSource source, double minRating, Long maxTs) {
    public PositiveFeedbackFilter { Objects.requireNonNull(source); }
}
