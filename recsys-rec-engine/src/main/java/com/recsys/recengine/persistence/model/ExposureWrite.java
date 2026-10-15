package com.recsys.recengine.persistence.model;

/** 异步曝光的最小不可变快照，位次明确为一基，避免持有上层推荐对象。 */
public record ExposureWrite(long itemId, String exposureId, int position) { }
