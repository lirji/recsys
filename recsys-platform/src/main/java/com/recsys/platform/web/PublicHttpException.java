package com.recsys.platform.web;

import com.recsys.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Objects;

/** Servlet受控HTTP错误；保持ResponseStatusException分类，同时允许目录内的兼容上下文提示。 */
public final class PublicHttpException extends ResponseStatusException {
    private final ErrorCode errorCode;

    /** 只接受已登记HTTP目录项，不能以任意reason或成功状态构造公开失败。 */
    public PublicHttpException(ErrorCode errorCode) {
        super(status(errorCode), errorCode.message());
        this.errorCode = errorCode;
    }

    /** 全局处理器从目录取文案，不读取可变ProblemDetail或异常原文。 */
    public ErrorCode errorCode() { return errorCode; }

    private static HttpStatus status(ErrorCode error) {
        Objects.requireNonNull(error, "errorCode");
        HttpStatus status = HttpStatus.resolve(error.httpStatus());
        if (status == null || !status.isError()) throw new IllegalArgumentException("公开HTTP错误须选择HTTP目录项");
        return status;
    }
}
