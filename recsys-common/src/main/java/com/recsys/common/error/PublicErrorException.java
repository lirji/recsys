package com.recsys.common.error;

import java.util.Objects;

/** 允许公开的失败只携带目录码；内部cause供诊断，禁止调用方传入任意用户文案。 */
public final class PublicErrorException extends RuntimeException {
    private final ErrorCode errorCode;

    /** 业务主动拒绝无需伪造内部异常。 */
    public PublicErrorException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    /** 保留内部失败因果，传输边界仍只输出目录内的安全文案。 */
    public PublicErrorException(ErrorCode errorCode, Throwable cause) {
        super(Objects.requireNonNull(errorCode, "errorCode").message(), cause);
        this.errorCode = errorCode;
    }

    /** 传输适配层据此选择协议状态，不能序列化cause或堆栈。 */
    public ErrorCode errorCode() { return errorCode; }
}
