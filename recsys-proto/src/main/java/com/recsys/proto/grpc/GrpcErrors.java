package com.recsys.proto.grpc;

import com.recsys.common.error.ErrorCode;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.function.Supplier;

/** gRPC只传目录内的安全错误；协议状态独立于HTTP，不传播上游描述或任意trailer。 */
public final class GrpcErrors {
    private static final System.Logger LOG = System.getLogger(GrpcErrors.class.getName());
    public static final Metadata.Key<String> ERROR_CODE =
            Metadata.Key.of("recsys-error-code", Metadata.ASCII_STRING_MARSHALLER);

    private GrpcErrors() {}

    /** 客户端只清除远端详情，保留异常类型/协议状态，让既有重试和fallback继续自行分类。 */
    public static <T> T call(ErrorCode unknownResult, Supplier<T> action) {
        try {
            return action.get();
        } catch (StatusRuntimeException failure) {
            throw sanitize(failure, unknownResult);
        }
    }

    /** 普通RPC原未捕获异常均为UNKNOWN；保留此状态，避免把新安全提示变成额外重试。 */
    public static <T> void respondWithUnknownOnFailure(StreamObserver<T> observer, ErrorCode error, Supplier<T> action) {
        T reply;
        try {
            reply = Objects.requireNonNull(action.get());
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "{0}; failureType={1}", error.displayMessage(), failure.getClass().getSimpleName());
            observer.onError(exception(Status.Code.UNKNOWN, error));
            return;
        }
        // 网络写入/取消引起的observer异常由gRPC负责，不再次发送onError或完成通知。
        observer.onNext(reply);
        observer.onCompleted();
    }

    /** 内部认证失败不是HTTP用户无权限；只识别匹配协议状态的单个明确caller码。 */
    public static boolean isInternalAccessFailure(StatusRuntimeException failure) {
        Status.Code status = failure.getStatus().getCode();
        if (status == Status.Code.UNAUTHENTICATED) return true;
        if (status != Status.Code.PERMISSION_DENIED || failure.getTrailers() == null) return false;
        Iterable<String> codes = failure.getTrailers().getAll(ERROR_CODE);
        if (codes == null) return false;
        var iterator = codes.iterator();
        if (!iterator.hasNext()) return false;
        String code = iterator.next();
        return !iterator.hasNext() && (ErrorCode.RPC_INTERNAL_CALLER_FORBIDDEN.code().equals(code)
                || ErrorCode.RPC_BUDGET_CALLER_FORBIDDEN.code().equals(code));
    }

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
            super(Status.fromCode(status).withDescription(error.displayMessage()), trailers(error));
            this.error = error;
        }
    }
}
