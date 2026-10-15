package com.recsys.proto.grpc;

import com.recsys.common.error.ErrorCode;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Objects;

/** gRPC只传目录内的安全错误；协议状态独立于HTTP，不传播上游描述或任意trailer。 */
public final class GrpcErrors {
    public static final Metadata.Key<String> ERROR_CODE =
            Metadata.Key.of("recsys-error-code", Metadata.ASCII_STRING_MARSHALLER);

    private GrpcErrors() {}

    /** 业务边界显式选择状态与目录码；构造失败不能用OK伪造成功。 */
    public static StatusRuntimeException exception(Status.Code status, ErrorCode error) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(error, "error");
        return status == Status.Code.OK
                ? new CatalogException(Status.Code.INTERNAL, ErrorCode.INTERNAL_ERROR)
                : new CatalogException(status, error);
    }

    /** 未知异常只保留协议分类；可信本地工厂也重新生成metadata，避免可变trailer混入详情。 */
    public static StatusRuntimeException sanitize(StatusRuntimeException failure, ErrorCode unknownResult) {
        Objects.requireNonNull(unknownResult, "unknownResult");
        Status.Code status = failure.getStatus().getCode();
        if (failure instanceof CatalogException known) return exception(status, known.error);
        ErrorCode safe = switch (status) {
            case UNAUTHENTICATED -> ErrorCode.UNAUTHENTICATED;
            case PERMISSION_DENIED -> ErrorCode.FORBIDDEN;
            case INVALID_ARGUMENT, OUT_OF_RANGE -> ErrorCode.BAD_REQUEST;
            case ALREADY_EXISTS, ABORTED -> ErrorCode.CONFLICT;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case RESOURCE_EXHAUSTED -> ErrorCode.TOO_MANY_REQUESTS;
            case CANCELLED -> ErrorCode.REQUEST_CANCELLED;
            default -> unknownResult;
        };
        return exception(status, safe);
    }

    private static Metadata trailers(ErrorCode error) {
        Metadata metadata = new Metadata();
        metadata.put(ERROR_CODE, error.code());
        return metadata;
    }

    /** 私有类型是进程内受控来源标记，远端伪造相同trailer不能获得信任。 */
    private static final class CatalogException extends StatusRuntimeException {
        private final ErrorCode error;

        private CatalogException(Status.Code status, ErrorCode error) {
            super(Status.fromCode(status).withDescription("[" + error.code() + "] " + error.message()), trailers(error));
            this.error = error;
        }
    }
}
