package com.recsys.recengine.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ClickSettlement.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.error.ErrorFamily;
import com.recsys.platform.web.ApiError;
import com.recsys.recengine.ad.AdServingGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 点击两个条件共用回执命名空间；HTTP快照省略主体和证明，等待不显示实收金额。 */
@RestController
@RequestMapping(value="/api/sandbox",produces="application/json")
@ConditionalOnProperty(prefix="recsys.ad.billing",name={"sandbox-enabled","clicks-enabled","click-settlement-enabled","click-delivery-enabled"},havingValue="true")
public class SandboxClickReceiptController {
    private final AdServingGateway gateway;
    public SandboxClickReceiptController(AdServingGateway gateway) { this.gateway=gateway; }
    /** 200接受/重复，422已持久化的政策或状态拒绝；未知结果保留原ID。 */
    @PutMapping("/click-receipts/{receiptId}")
    public ResponseEntity<ReceiptView> accept(@PathVariable String receiptId,@RequestBody Input input,Authentication auth) {
        var subject=authorize(auth);
        if(input.kind()==null || !input.kind().isTextual() || input.visibleDurationMs()==null || !input.visibleDurationMs().isIntegralNumber()
                || !input.visibleDurationMs().canConvertToLong() || input.minVisibleRatioBps()==null || !input.minVisibleRatioBps().isIntegralNumber()
                || !input.minVisibleRatioBps().canConvertToInt() || input.pageVisible()==null || !input.pageVisible().isBoolean()) {
            throw new IllegalArgumentException("点击观测格式非法");
        }
        var result=gateway.acceptSandboxClick(new Command(BudgetCommand.parseId(receiptId),subject,input.qualificationToken(),Kind.valueOf(input.kind().textValue()),
                input.visibleDurationMs().longValue(),input.minVisibleRatioBps().intValue(),input.pageVisible().booleanValue()));
        boolean accepted=switch(result.receipt().status()) {
            case ACCEPTED_WAITING,ACCEPTED_CAPTURED,ALREADY_ACCEPTED -> true;
            default -> false;
        };
        return ResponseEntity.status(accepted?HttpStatus.OK:HttpStatus.UNPROCESSABLE_ENTITY).body(view(result));
    }
    /** 原决定不变，但查询附带当前结算状态，避免把WAITING误当最终结果。 */
    @GetMapping("/click-receipts/{receiptId}")
    public ReceiptView receipt(@PathVariable String receiptId,Authentication auth) {
        var subject=authorize(auth);
        return view(gateway.findSandboxClickReceipt(BudgetCommand.parseId(receiptId),subject).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
    /** 查询资金owner，不以准备时的HELD推断当前仍可收费。 */
    @GetMapping("/click-settlements/{qualificationId}")
    public SettlementView settlement(@PathVariable String qualificationId,Authentication auth) {
        var subject=authorize(auth);
        return view(gateway.findSandboxClickSettlement(BudgetCommand.parseId(qualificationId),subject).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
    private String authorize(Authentication auth) {
        if(auth==null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(auth.getAuthorities().stream().noneMatch(a->"ROLE_ADMIN".equals(a.getAuthority())))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Reservation.validateSubject(auth.getName());return auth.getName();
    }
    /** 领域错误与依赖未知区分，响应不回显异常信息/SQL/token。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> failure(Exception ex,HttpServletRequest request) {
        HttpStatusCode status=ex instanceof ClickSettlement.Conflict?HttpStatus.CONFLICT
                :ex instanceof SandboxDelivery.InvalidProof || ex instanceof Reservation.AccessDenied?HttpStatus.FORBIDDEN
                :ex instanceof IllegalArgumentException || ex instanceof org.springframework.http.converter.HttpMessageNotReadableException?HttpStatus.BAD_REQUEST
                :ex instanceof ResponseStatusException r?r.getStatusCode():HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(ApiError.of(ErrorFamily.CLICK_RECEIPT, status.value(), request.getRequestURI(), null));
    }
    private static ReceiptView view(Result result) { return new ReceiptView(result.receipt(),view(result.settlement())); }
    private static SettlementView view(Snapshot s) {
        var p=s.prepared();
        return new SettlementView(p.qualificationId(),p.decision(),p.budgetDate(),p.amount(),p.issuedAt(),p.expiresAt(),p.preparedAt(),
                s.state(),s.progress(),s.version(),s.visibleReceiptId(),s.clickReceiptId(),s.ledgerId(),s.chargedAmount(),s.closedAt(),s.closeReason());
    }
    public record Input(String qualificationToken,JsonNode kind,JsonNode visibleDurationMs,JsonNode minVisibleRatioBps,JsonNode pageVisible) {}
    public record ReceiptView(Receipt receipt,SettlementView settlement) {}
    public record SettlementView(UUID qualificationId,Decision decision,LocalDate budgetDate,String quotedAmount,Instant issuedAt,Instant expiresAt,
            Instant preparedAt,State state,Progress progress,long version,UUID visibleReceiptId,UUID clickReceiptId,UUID ledgerId,String chargedAmount,
            Instant closedAt,CloseReason closeReason) {}
}
