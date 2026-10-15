package com.recsys.behavior.exposure;

import com.fasterxml.jackson.databind.JsonNode;
import com.recsys.common.exposure.NaturalReceipt;
import com.recsys.common.exposure.NaturalReceipt.*;
import com.recsys.platform.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;

/** ADMIN sandbox 自然回执；错误只给安全分类，绝不回显 token、SQL 或认证主体。 */
@RestController
@RequestMapping(value = "/api/sandbox/natural-impressions", produces = "application/json")
@ConditionalOnProperty(name = "recsys.natural.receipts-enabled", havingValue = "true")
public class NaturalReceiptController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(NaturalReceiptController.class);
    private static final String ADMIN_ROLE = "ROLE_ADMIN";
    private static final Set<String> INPUT_FIELDS = Set.of("qualificationToken", "visibleDurationMs", "minVisibleRatioBps", "pageVisible");
    private final NaturalReceiptService service;
    public NaturalReceiptController(NaturalReceiptService service) { this.service = service; }

    /** JsonNode 保留严格整数/布尔类型，避免字符串或小数被 Jackson 静默转换。 */
    @PutMapping("/{receiptId}")
    public ResponseEntity<Result> accept(@PathVariable String receiptId, @RequestBody JsonNode input, Authentication auth) {
        String subject = authorize(auth);
        if (input == null || !input.isObject() || input.size() != INPUT_FIELDS.size()) throw new IllegalArgumentException("回执字段非法");
        input.fieldNames().forEachRemaining(key -> { if (!INPUT_FIELDS.contains(key)) throw new IllegalArgumentException("回执字段非法"); });
        var token = input.path("qualificationToken"); var visible = input.path("pageVisible");
        if (!token.isTextual() || !visible.isBoolean()) throw new IllegalArgumentException("回执字段类型非法");
        var result = service.accept(new Command(NaturalReceipt.parseId(receiptId), subject, token.textValue(),
                integer(input.path("visibleDurationMs")), integer(input.path("minVisibleRatioBps")), visible.booleanValue()));
        return ResponseEntity.status(result.status() == Status.ACCEPTED || result.status() == Status.ALREADY_ACCEPTED
                ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY).body(result);
    }
    /** 历史业务拒绝也以 200 返回已知原决定，不重新执行或覆盖。 */
    @GetMapping("/{receiptId}")
    public Result get(@PathVariable String receiptId, Authentication auth) {
        String subject = authorize(auth);
        return service.find(NaturalReceipt.parseId(receiptId), subject)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "当前未观察到回执"));
    }
    /** owner 自身再次授权，不能只依赖网关的路径规则。 */
    private String authorize(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (auth.getAuthorities().stream().noneMatch(a -> ADMIN_ROLE.equals(a.getAuthority()))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        NaturalReceipt.validateSubject(auth.getName()); return auth.getName();
    }
    private static int integer(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw new IllegalArgumentException("观测必须为整数");
        return node.intValue();
    }
    /** 系统失败保持结果未知；允许同 receiptId 查询/重试，不能兜底成功。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> failure(Exception ex, HttpServletRequest request) {
        int status = ex instanceof Conflict ? HttpStatus.CONFLICT.value() : ex instanceof AccessDenied ? HttpStatus.FORBIDDEN.value()
                : ex instanceof IllegalArgumentException || ex instanceof org.springframework.http.converter.HttpMessageNotReadableException ? HttpStatus.BAD_REQUEST.value()
                : ex instanceof ResponseStatusException r ? r.getStatusCode().value() : HttpStatus.SERVICE_UNAVAILABLE.value();
        if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            String id = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
            LOG.warn("natural exposure result unknown operationOrReceiptId={} failureType={}",
                    id.matches("[0-9a-fA-F-]{36}") ? id : "invalid", ex.getClass().getSimpleName());
        }
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(ApiError.of("NATURAL_RECEIPT_" + status,
                status == HttpStatus.SERVICE_UNAVAILABLE.value() ? "自然回执结果未知，请保留原 receiptId 查询或重试" : "自然回执未通过校验或当前不可访问",
                request.getRequestURI(), null));
    }
}
