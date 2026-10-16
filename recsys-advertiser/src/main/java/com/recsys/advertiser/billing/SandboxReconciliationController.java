package com.recsys.advertiser.billing;

import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.common.ad.billing.BillingReconciliation.Scope;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.error.ErrorCode;
import com.recsys.platform.web.ApiError;
import com.recsys.platform.web.PublicHttpException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 原路由下独立只读资金口径，每次强制view关系；无任务/恢复/收费写入口。 */
@RestController
@RequestMapping(value = "/api/advertiser/{advertiserId}/sandbox-reconciliation", produces = "application/json")
@ConditionalOnProperty(name = {"recsys.ad.billing.sandbox-enabled", "recsys.advertiser.sandbox-reconciliation-enabled"}, havingValue = "true")
public class SandboxReconciliationController {
    private final AdvertiserAuthz authz;
    private final GrpcSandboxReconciliationClient client;
    /** 授权守卫完成后才允许跨进程查询owner。 */
    public SandboxReconciliationController(AdvertiserAuthz authz, GrpcSandboxReconciliationClient client) { this.authz = authz; this.client = client; }

    /** HTTP禁止缓存，撤权后的下一次读取必须重新经过服务端关系验证。 */
    @GetMapping("/{budgetDate}")
    public ResponseEntity<SandboxReconciliationView> inspect(@PathVariable String advertiserId, @PathVariable String budgetDate, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_ADVERTISER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (!advertiserId.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("广告主ID格式非法");
        var scope = new Scope(Long.parseLong(advertiserId), BudgetCommand.parseDate(budgetDate));
        var subject = authz.requireReconciliationView(scope.advertiserId());
        if (!authentication.getName().equals(subject)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SandboxReconciliationView.from(client.inspect(scope)));
    }
    /** 只输出目录文案，不将依赖、SQL或授权SDK异常详情返回浏览器。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> failure(Exception ex, HttpServletRequest request) {
        var code = ex instanceof PublicHttpException safe ? safe.errorCode()
                : ex instanceof ResponseStatusException status ? ErrorCode.forHttpStatus(status.getStatusCode().value())
                : ex instanceof IllegalArgumentException || ex instanceof java.time.DateTimeException ? ErrorCode.RECONCILIATION_INVALID_ARGUMENT
                : ErrorCode.RECONCILIATION_UNAVAILABLE;
        return ResponseEntity.status(code.httpStatus()).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .body(ApiError.of(code, request.getRequestURI(), null));
    }
}
