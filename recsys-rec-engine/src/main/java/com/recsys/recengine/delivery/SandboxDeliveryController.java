package com.recsys.recengine.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.Reservation;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.platform.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** 首版仅认证 ADMIN 的 sandbox 入口；价格和主体从未作为终端请求字段接受。 */
@RestController
@RequestMapping(value = "/api/sandbox/search-ad-deliveries", produces = "application/json")
@ConditionalOnProperty(name = "recsys.ad.billing.sandbox-enabled", havingValue = "true")
public class SandboxDeliveryController {
    private final DeliveryOperationService service;
    public SandboxDeliveryController(@org.springframework.beans.factory.annotation.Qualifier("deliveryOperationService") DeliveryOperationService service) { this.service = service; }

    /** 完成仅指准备结果齐全，前端仍须可见回执才能计费。 */
    @PutMapping("/{operationId}")
    public ResponseEntity<View> deliver(@PathVariable String operationId, @RequestBody Input input, Authentication authentication) {
        String subject = authorize(authentication);
        if (input.userId() == null || !input.userId().isTextual() || !input.userId().textValue().matches("[1-9][0-9]{0,18}") || input.slots() == null
                || !input.slots().isIntegralNumber() || !input.slots().canConvertToInt()) throw new IllegalArgumentException("userId/slots 格式非法");
        var operation = service.deliver(new DeliveryOperation.Request(BudgetCommand.parseId(operationId), subject,
                Long.parseLong(input.userId().textValue()), input.query(), input.slots().intValue(), input.scene()));
        return ResponseEntity.status(operation.state() == DeliveryOperation.State.COMPLETE ? HttpStatus.OK : HttpStatus.ACCEPTED).body(view(operation));
    }
    /** 查询需原主体，不因知道 UUID 获得访问权。 */
    @GetMapping("/{operationId}")
    public View get(@PathVariable String operationId, Authentication authentication) {
        return view(service.find(BudgetCommand.parseId(operationId), authorize(authentication))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "当前未观察到交付操作")));
    }
    private String authorize(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (auth.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Reservation.validateSubject(auth.getName());
        return auth.getName();
    }
    /** 局部错误映射不将未知执行结果伪造成拒绝；不泄漏 token 或 SQL。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> failure(Exception ex, HttpServletRequest request) {
        int status = ex instanceof SandboxDelivery.Conflict ? 409 : ex instanceof Reservation.AccessDenied ? 403
                : ex instanceof IllegalArgumentException || ex instanceof org.springframework.http.converter.HttpMessageNotReadableException ? 400
                : ex instanceof ResponseStatusException r ? r.getStatusCode().value() : 503;
        return ResponseEntity.status(status).body(ApiError.of("DELIVERY_" + status,
                status == HttpStatus.SERVICE_UNAVAILABLE.value() ? "交付结果未知，请保留原 operationId 查询或重试" : "交付请求未通过校验或当前不可访问", request.getRequestURI(), null));
    }
    private static View view(DeliveryOperation operation) {
        var r = operation.request();
        return new View(r.operationId().toString(), "SANDBOX", operation.state().name(), Long.toString(r.userId()), r.query(), r.scene(), r.slots(),
                operation.plan() == null ? List.of() : operation.plan().stream().map(e -> {
                    var ad = e.candidate().ad();
                    return new Item(e.slot(), e.candidate().childId().toString(), Long.toString(ad.adId()), Long.toString(ad.itemId()),
                            Long.toString(ad.advertiserId()), Long.toString(ad.creativeId()), ad.title(), e.result());
                }).toList());
    }
    public record Input(JsonNode userId, String query, JsonNode slots, String scene) {}
    public record View(String operationId, String trafficClass, String state, String userId, String query, String scene, int slots, List<Item> items) {}
    public record Item(int slot, String childId, String adId, String itemId, String advertiserId, String creativeId,
                       String title, SandboxDelivery.Prepared preparation) {}
}
