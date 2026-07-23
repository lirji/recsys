package com.recsys.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全站统一异常处理(P0)。把校验失败 / 越权 / 业务异常 / 未捕获异常统一映射为 {@link ApiError},
 * 消除各服务裸抛 500 与栈信息泄漏。
 *
 * <p>放在技术平台模块,用 {@code @ConditionalOnClass(DispatcherServlet)} 守卫:只有 Servlet Web 应用才注册;
 * 即使某个只跑 gRPC 的服务误引入本模块,无 webmvc 时也在扫描期按元数据跳过。
 */
@RestControllerAdvice
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** @Valid 请求体校验失败(POST/PUT body)。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_FAILED", "请求参数校验失败", req.getRequestURI(), fields));
    }

    /** @Validated 方法参数 / path/query 参数约束失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleParamValidation(ConstraintViolationException ex, HttpServletRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getConstraintViolations()
                .forEach(v -> fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_FAILED", "请求参数校验失败", req.getRequestURI(), fields));
    }

    /** path/query 参数无法转换到控制器声明的类型，例如 userId=u1 → long。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        String requiredType = ex.getRequiredType() == null
                ? "正确类型"
                : ex.getRequiredType().getSimpleName();
        fields.put(ex.getName(), "类型错误，应为 " + requiredType);
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_FAILED", "请求参数校验失败", req.getRequestURI(), fields));
    }

    /**
     * Spring 的 ErrorResponseException 家族,按异常自带状态码透传。
     * 覆盖 ResponseStatusException(业务主动抛 4xx/5xx)与 NoResourceFoundException(404 未匹配路由/资源)——
     * 后者必须保持 404,不能被下面的 Exception 兜底吞成 500。
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ApiError> handleErrorResponse(ErrorResponseException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String detail = ex.getBody() != null ? ex.getBody().getDetail() : null;
        String message = detail != null ? detail : status.getReasonPhrase();
        return ResponseEntity.status(status)
                .body(ApiError.of(status.name(), message, req.getRequestURI(), null));
    }

    /** 方法级 @PreAuthorize 拒绝(URL 级拒绝由 Security 的 accessDeniedHandler 处理)。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleDenied(AccessDeniedException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "无权访问该资源", req.getRequestURI(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArg(IllegalArgumentException ex, HttpServletRequest req) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("BAD_REQUEST", ex.getMessage(), req.getRequestURI(), null));
    }

    /** 兜底:未预期异常记录完整栈到日志,但只向客户端返回脱敏信息。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "服务器内部错误", req.getRequestURI(), null));
    }
}
