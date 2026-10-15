package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

/** 分发器和DAG共享失败分类，错误摘要不包含内部异常或用户传入文本。 */
final class JobFailures {
    private JobFailures() { }
    /** 不以任意异常文本猜测业务原因；明确的作业目录异常保留其可行动说明。 */
    static PublicErrorException publicFailure(Exception failure) {
        if (failure instanceof PublicErrorException safe && safe.errorCode().code().startsWith("JOB_")) return safe;
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        return new PublicErrorException(ErrorCode.JOB_EXECUTION_FAILED, failure);
    }
}
