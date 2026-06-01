package com.recsys.embedding;

/**
 * 向量化 API 配额耗尽(HTTP 429)。区别于普通失败:重试无意义,
 * 调用方应停止批量作业(如灌向量),等额度恢复再续跑。
 */
public class QuotaExhaustedException extends RuntimeException {
    public QuotaExhaustedException(String message) {
        super(message);
    }
}
