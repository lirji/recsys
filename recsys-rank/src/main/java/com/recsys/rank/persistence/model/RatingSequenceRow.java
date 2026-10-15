package com.recsys.rank.persistence.model;

/** epoch保留浮点值及NULL，避免数据库bigint转换的舍入改变原Redis回填时间。 */
public record RatingSequenceRow(long itemId, Double epochSeconds) {

    /** 沿用原getDouble后Java强转：NULL为零，小数向零截断。 */
    public long[] asTimestampPair() {
        return new long[]{itemId, epochSeconds == null ? 0 : (long) epochSeconds.doubleValue()};
    }
}
