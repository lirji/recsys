package com.recsys.recengine.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.platform.web.ApiError;
import com.recsys.recengine.ad.AdServingGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** sandbox 回执仅接受原认证管理员的观测声明，不接受价格和广告主参数。 */
@RestController
@RequestMapping(value="/api/sandbox/ad-impressions", produces="application/json")
@ConditionalOnProperty(prefix="recsys.ad.billing", name={"sandbox-enabled","receipts-enabled"}, havingValue="true")
public class SandboxImpressionController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SandboxImpressionController.class);
    private final AdServingGateway gateway;
    public SandboxImpressionController(AdServingGateway gateway) { this.gateway=gateway; }
    /** 首次接受或业务重复 200；持久化政策/状态拒绝 422，不能伪成功。 */
    @PutMapping("/{receiptId}")
    public ResponseEntity<ImpressionReceipt.Result> accept(@PathVariable String receiptId, @RequestBody Input input, Authentication auth) {
        String subject = authorize(auth);
        if (input.visibleDurationMs()==null || !input.visibleDurationMs().isIntegralNumber() || !input.visibleDurationMs().canConvertToLong()
                || input.minVisibleRatioBps()==null || !input.minVisibleRatioBps().isIntegralNumber() || !input.minVisibleRatioBps().canConvertToInt()
                || input.pageVisible()==null || !input.pageVisible().isBoolean()) throw new IllegalArgumentException("观测时长/比例须为整数，页面状态须为布尔值");
        var result = gateway.acceptSandboxImpression(new ImpressionReceipt.Command(BudgetCommand.parseId(receiptId), subject, input.qualificationToken(),
                input.visibleDurationMs().longValue(), input.minVisibleRatioBps().intValue(), input.pageVisible().booleanValue()));
        return ResponseEntity.status(result.accepted()?HttpStatus.OK:HttpStatus.UNPROCESSABLE_ENTITY).body(result);
    }
    /** 查询原结果，404 只说明当前未观察到，不授予更换 ID 的语义。 */
    @GetMapping("/{receiptId}")
    public ImpressionReceipt.Result find(@PathVariable String receiptId, Authentication auth) {
        return gateway.findSandboxImpression(BudgetCommand.parseId(receiptId), authorize(auth))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private String authorize(Authentication auth) {
        if (auth==null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (auth.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Reservation.validateSubject(auth.getName()); return auth.getName();
    }
    /** 统一将本地/远程领域错误转换为相同 HTTP 语义，不输出内部栈或 token。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> failure(Exception ex, HttpServletRequest request) {
        HttpStatusCode status = ex instanceof ImpressionReceipt.Conflict ? HttpStatus.CONFLICT
                : ex instanceof SandboxDelivery.InvalidProof || ex instanceof Reservation.AccessDenied ? HttpStatus.FORBIDDEN
                : ex instanceof IllegalArgumentException || ex instanceof org.springframework.http.converter.HttpMessageNotReadableException ? HttpStatus.BAD_REQUEST
                : ex instanceof ResponseStatusException r ? r.getStatusCode() : HttpStatus.SERVICE_UNAVAILABLE;
        if (status.equals(HttpStatus.SERVICE_UNAVAILABLE)) {
            String id = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
            LOG.warn("sandbox receipt result unknown receiptId={} failureType={}",
                    id.matches("[0-9a-fA-F-]{36}") ? id : "invalid", ex.getClass().getSimpleName());
        }
        return ResponseEntity.status(status).body(ApiError.of("RECEIPT_"+status.value(), status.equals(HttpStatus.SERVICE_UNAVAILABLE)
                ? "账务结果未知，请按原 receiptId 查询或重试" : "回执请求未通过校验或当前不可访问", request.getRequestURI(), null));
    }
    public record Input(String qualificationToken, JsonNode visibleDurationMs, JsonNode minVisibleRatioBps, JsonNode pageVisible) {}
}
