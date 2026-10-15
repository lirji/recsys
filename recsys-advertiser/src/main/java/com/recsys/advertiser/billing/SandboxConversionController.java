package com.recsys.advertiser.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ConversionSettlement.*;
import com.recsys.common.error.ErrorFamily;
import com.recsys.platform.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

/** CPA业务入口每次强制归属判权，不能把持有资格证明当成广告主权限。 */
@RestController
@RequestMapping(value="/api/advertiser/{advertiserId}/sandbox-conversions",produces="application/json")
@ConditionalOnProperty(prefix="recsys.ad.billing",name={"sandbox-enabled","conversions-enabled","conversion-settlement-enabled","conversion-delivery-enabled"},havingValue="true")
public class SandboxConversionController {
    private final AdvertiserAuthz authz;
    private final GrpcSandboxConversionClient client;
    public SandboxConversionController(AdvertiserAuthz authz,GrpcSandboxConversionClient client) {this.authz=authz;this.client=client;}
    /** 业务键只取路径，actor只取本次授权；不接受金额、用户或风控结果。 */
    @PutMapping("/{eventId}")
    public ResponseEntity<View> accept(@PathVariable long advertiserId,@PathVariable String eventId,@RequestBody Input input,Authentication authentication) {
        var actor=authorize(advertiserId,authentication);
        if(input.qualificationToken()==null || !input.qualificationToken().isTextual() || input.occurredAt()==null || !input.occurredAt().isTextual()
                || !input.occurredAt().textValue().endsWith("Z")) throw new IllegalArgumentException("资格及UTC发生时间须为字符串");
        var command=new ConversionCommand(advertiserId,BudgetCommand.parseId(eventId),actor,input.qualificationToken().textValue(),Instant.parse(input.occurredAt().textValue()));
        var result=client.accept(command);
        boolean accepted=switch(result.outcome().status()){case ACCEPTED_WAITING,ACCEPTED_CAPTURED,DUPLICATE_QUALIFICATION->true;default->false;};
        return ResponseEntity.status(accepted?HttpStatus.OK:HttpStatus.UNPROCESSABLE_ENTITY).body(view(result));
    }
    /** 原结果恢复也重新判权，防止已撤销成员仅凭业务ID读取历史。 */
    @GetMapping("/{eventId}")
    public View find(@PathVariable long advertiserId,@PathVariable String eventId,Authentication authentication) {
        var actor=authorize(advertiserId,authentication);return view(client.find(advertiserId,BudgetCommand.parseId(eventId),actor));
    }
    private String authorize(long advertiserId,Authentication auth) {
        if(auth==null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(auth.getAuthorities().stream().noneMatch(a->"ROLE_ADVERTISER".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority())))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var actor=authz.requireConversionSubmission(advertiserId);
        // 防止外部身份参数与守卫SecurityContext意外不一致时继续执行资金操作。
        if(!actor.equals(auth.getName()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return actor;
    }
    /** 错误只返回受控分类，依赖失败不伪造成零收费或业务拒绝。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> failure(Exception ex,HttpServletRequest request) {
        HttpStatusCode status=ex instanceof ResponseStatusException r?r.getStatusCode():ex instanceof ConversionSettlement.Conflict?HttpStatus.CONFLICT
                :ex instanceof IllegalArgumentException || ex instanceof java.time.DateTimeException || ex instanceof org.springframework.http.converter.HttpMessageNotReadableException?HttpStatus.BAD_REQUEST:HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(ApiError.of(ErrorFamily.CONVERSION, status.value(), request.getRequestURI(), null));
    }
    private static View view(OutcomeResult r) {
        var o=r.outcome();var s=r.settlement();var p=s.prepared();
        boolean charged=s.state()==State.CAPTURED && o.outcomeId().equals(s.outcomeId());
        return new View(o.outcomeId().toString(),Long.toString(o.advertiserId()),o.eventId().toString(),o.qualificationId().toString(),o.status(),
                o.occurredAt(),o.receivedAt(),o.decidedAt(),o.canonicalOutcomeId()==null?null:o.canonicalOutcomeId().toString(),charged,charged?s.chargedAmount():null,
                new SettlementView(s.state(),s.progress(),p.budgetDate().toString(),p.amount(),s.chargedAmount(),s.ledgerId()==null?null:s.ledgerId().toString(),s.closedAt()));
    }
    public record Input(JsonNode qualificationToken,JsonNode occurredAt) {}
    public record View(String outcomeId,String advertiserId,String eventId,String qualificationId,OutcomeStatus status,Instant occurredAt,Instant receivedAt,
                       Instant decidedAt,String canonicalOutcomeId,boolean chargedForThisOutcome,String chargedAmount,SettlementView settlement) {}
    public record SettlementView(State state,Progress progress,String budgetDate,String quotedAmount,String chargedAmount,String ledgerId,Instant closedAt) {}
}
