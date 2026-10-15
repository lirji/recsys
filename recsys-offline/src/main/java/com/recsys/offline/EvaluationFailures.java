package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 评估允许保留成功用户的结果，但不能把缺失用户后的部分产物标记为完整成功。 */
final class EvaluationFailures {
    private static final Logger log = LoggerFactory.getLogger(EvaluationFailures.class);
    private Throwable firstCause;
    private int count;

    /** 由串行收集线程调用，只保留首个原因，避免为每个失败用户积累异常对象。 */
    void record(Throwable failure) {
        if (firstCause == null) firstCause = failure;
        count++;
    }

    /** 发布条件不满足时优先报告故障；允许部分发布时在发布后调用。 */
    void throwIfAny() {
        if (count == 0) return;
        log.warn("{}；失败用户评估次数={}", ErrorCode.JOB_EVALUATION_PARTIAL_FAILED.displayMessage(), count);
        throw new PublicErrorException(ErrorCode.JOB_EVALUATION_PARTIAL_FAILED, firstCause);
    }
}
