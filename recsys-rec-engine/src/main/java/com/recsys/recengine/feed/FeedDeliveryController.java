package com.recsys.recengine.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.recsys.common.exposure.*;
import com.recsys.platform.web.ApiError;
import com.recsys.recengine.feed.FeedDelivery.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;

/** 混排 sandbox 整次交付协议；只有 ADMIN 原主体可发起与恢复。 */
@RestController
@RequestMapping(value="/api/sandbox/feed-deliveries",produces="application/json")
@ConditionalOnProperty(name="recsys.feed.delivery-enabled",havingValue="true")
public class FeedDeliveryController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FeedDeliveryController.class);
    private static final String ADMIN_ROLE="ROLE_ADMIN";
    private static final Set<String> INPUT_FIELDS=Set.of("userId","size","scene","query");
    private final FeedDeliveryService service;
    public FeedDeliveryController(FeedDeliveryService service) { this.service=service; }
    /** 原始 JSON 类型严格校验，大 ID 禁止以浮点数字送入。 */
    @PutMapping("/{operationId}")
    public ResponseEntity<View> deliver(@PathVariable String operationId,@RequestBody JsonNode input,Authentication auth) {
        String subject=authorize(auth);
        if(input==null || !input.isObject()) throw new IllegalArgumentException("混排交付格式非法");
        input.fieldNames().forEachRemaining(key->{ if(!INPUT_FIELDS.contains(key)) throw new IllegalArgumentException("混排交付字段非法"); });
        var user=input.path("userId"); var size=input.path("size");
        if(!user.isTextual() || !user.textValue().matches("[1-9][0-9]{0,18}") || !size.isIntegralNumber() || !size.canConvertToInt()) throw new IllegalArgumentException("混排交付身份/数量非法");
        var operation=service.deliver(new Request(NaturalReceipt.parseId(operationId),subject,Long.parseLong(user.textValue()),
                size.intValue(),text(input.path("scene"),false),text(input.path("query"),true)));
        return ResponseEntity.status(operation.state()==State.COMPLETE?HttpStatus.OK:HttpStatus.ACCEPTED).body(FeedDelivery.view(operation));
    }
    /** 当前查无结果不代表原 PUT 未提交，调用方须保留同 ID。 */
    @GetMapping("/{operationId}")
    public View get(@PathVariable String operationId,Authentication auth) {
        String subject=authorize(auth);
        return FeedDelivery.view(service.find(NaturalReceipt.parseId(operationId),subject).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
    private static String text(JsonNode value,boolean nullable) {
        if(nullable && (value.isMissingNode() || value.isNull())) return null;
        if(!value.isTextual()) throw new IllegalArgumentException("混排交付文本类型非法");
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
            LOG.warn("feed exposure result unknown operationOrReceiptId={} failureType={}",
                    id.matches("[0-9a-fA-F-]{36}") ? id : "invalid", ex.getClass().getSimpleName());
        }
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(ApiError.of("FEED_DELIVERY_"+status,
                status==HttpStatus.SERVICE_UNAVAILABLE.value()?"混排交付结果未知，请保留原 operationId 查询或重试":"混排交付请求未通过校验或当前不可访问",request.getRequestURI(),null));
    }
}
