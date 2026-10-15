package com.recsys.recengine.exposure;

import com.fasterxml.jackson.databind.JsonNode;
import com.recsys.common.exposure.*;
import com.recsys.common.error.ErrorFamily;
import com.recsys.platform.web.ApiError;
import com.recsys.recengine.exposure.NaturalDelivery.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;

/** 自然 sandbox 整次交付协议；只有 ADMIN 原主体可发起与恢复。 */
@RestController
@RequestMapping(value="/api/sandbox/natural-deliveries",produces="application/json")
@ConditionalOnProperty(name="recsys.natural.delivery-enabled",havingValue="true")
public class NaturalDeliveryController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(NaturalDeliveryController.class);
    private static final String ADMIN_ROLE="ROLE_ADMIN";
    private static final Set<String> INPUT_FIELDS=Set.of("userId","kind","size","scene","query");
    private final NaturalDeliveryService service;
    public NaturalDeliveryController(NaturalDeliveryService service) { this.service=service; }
    /** 原始 JSON 类型严格校验，大 ID 禁止以浮点数字送入。 */
    @PutMapping("/{operationId}")
    public ResponseEntity<View> deliver(@PathVariable String operationId,@RequestBody JsonNode input,Authentication auth) {
        String subject=authorize(auth);
        if(input==null || !input.isObject()) throw new IllegalArgumentException("自然交付格式非法");
        input.fieldNames().forEachRemaining(key->{ if(!INPUT_FIELDS.contains(key)) throw new IllegalArgumentException("自然交付字段非法"); });
        var user=input.path("userId"); var size=input.path("size");
        if(!user.isTextual() || !user.textValue().matches("[1-9][0-9]{0,18}") || !size.isIntegralNumber() || !size.canConvertToInt()) throw new IllegalArgumentException("自然交付身份/数量非法");
        var result=service.deliver(new Request(NaturalReceipt.parseId(operationId),subject,Long.parseLong(user.textValue()),
                NaturalQualification.Kind.valueOf(text(input.path("kind"),false)),size.intValue(),text(input.path("scene"),false),text(input.path("query"),true)));
        return ResponseEntity.status(result.state()==State.COMPLETE?HttpStatus.OK:HttpStatus.ACCEPTED).body(result);
    }
    /** 当前查无结果不代表原 PUT 未提交，调用方须保留同 ID。 */
    @GetMapping("/{operationId}")
    public View get(@PathVariable String operationId,Authentication auth) {
        String subject=authorize(auth);
        return service.find(NaturalReceipt.parseId(operationId),subject).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private static String text(JsonNode value,boolean nullable) {
        if(nullable && (value.isMissingNode() || value.isNull())) return null;
        if(!value.isTextual()) throw new IllegalArgumentException("自然交付文本类型非法");
        return value.textValue();
    }
    private String authorize(Authentication auth) {
        if(auth==null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(auth.getAuthorities().stream().noneMatch(a->ADMIN_ROLE.equals(a.getAuthority()))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        NaturalReceipt.validateSubject(auth.getName()); return auth.getName();
    }
    /** 依赖失败与提交未知都返回 503，不将空列表当失败兜底。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> failure(Exception ex,HttpServletRequest request) {
        int status=ex instanceof NaturalReceipt.Conflict?HttpStatus.CONFLICT.value():ex instanceof NaturalReceipt.AccessDenied?HttpStatus.FORBIDDEN.value()
                :ex instanceof IllegalArgumentException || ex instanceof org.springframework.http.converter.HttpMessageNotReadableException?HttpStatus.BAD_REQUEST.value()
                :ex instanceof ResponseStatusException r?r.getStatusCode().value():HttpStatus.SERVICE_UNAVAILABLE.value();
        if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            String id = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
            LOG.warn("natural exposure result unknown operationOrReceiptId={} failureType={}",
                    id.matches("[0-9a-fA-F-]{36}") ? id : "invalid", ex.getClass().getSimpleName());
        }
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(ApiError.of(ErrorFamily.NATURAL_DELIVERY, status, request.getRequestURI(), null));
    }
}
