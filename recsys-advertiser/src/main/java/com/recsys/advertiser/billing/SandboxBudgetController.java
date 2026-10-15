package com.recsys.advertiser.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.recsys.advertiser.AdvertiserRepository;
import com.recsys.common.ad.billing.BudgetAccount;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.BudgetCommandResult;
import com.recsys.common.ad.billing.CnyAmount;
import com.recsys.platform.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 首版 sandbox 管理接口；显式检查认证管理员，不能被旧 nosec/禁用方法安全绕过。 */
@RestController
@RequestMapping(value = "/api/advertiser/{advertiserId}", produces = "application/json")
@ConditionalOnProperty(name = "recsys.ad.billing.sandbox-enabled", havingValue = "true")
public class SandboxBudgetController {
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
    private final AdvertiserRepository advertisers;
    private final GrpcSandboxBudgetClient client;

    public SandboxBudgetController(AdvertiserRepository advertisers, GrpcSandboxBudgetClient client) {
        this.advertisers = advertisers;
        this.client = client;
    }

    /** 修改额度与命令结果绑定；actor 仅从认证上下文生成。 */
    @PutMapping(value = "/sandbox-budget/{budgetDate}", consumes = "application/json")
    public ResponseEntity<CommandView> setLimit(@PathVariable long advertiserId, @PathVariable String budgetDate,
            @RequestBody LimitRequest request, Authentication authentication) {
        authorize(authentication, advertiserId);
        if (request.limitAmount() == null || !request.limitAmount().isTextual() || request.expectedVersion() == null
                || !request.expectedVersion().isIntegralNumber() || !request.expectedVersion().canConvertToLong()) {
            throw new IllegalArgumentException("limitAmount 必须为字符串，expectedVersion 必须为整数");
        }
        BudgetCommand command = BudgetCommand.parse(advertiserId, request.commandId(), budgetDate,
                request.currency(), request.limitAmount().textValue(), request.expectedVersion().longValue(), authentication.getName());
        BudgetCommandResult result = client.setLimit(command);
        return ResponseEntity.status(result.status() == BudgetCommandResult.Status.APPLIED ? HttpStatus.OK : HttpStatus.CONFLICT)
                .body(CommandView.from(result));
    }

    /** 当前账户与命令历史分开查询，避免误用最新状态覆盖首次结果。 */
    @GetMapping("/sandbox-budget/{budgetDate}")
    public AccountView getAccount(@PathVariable long advertiserId, @PathVariable String budgetDate,
                                  Authentication authentication) {
        authorize(authentication, advertiserId);
        return AccountView.from(client.getAccount(advertiserId, BudgetCommand.parseDate(budgetDate)));
    }

    /** 查询成功统一 200，业务执行结果由 status 明示。 */
    @GetMapping("/sandbox-budget-commands/{commandId}")
    public CommandView getCommand(@PathVariable long advertiserId, @PathVariable String commandId,
                                  Authentication authentication) {
        authorize(authentication, advertiserId);
        return CommandView.from(client.getCommand(advertiserId, BudgetCommand.parseId(commandId)));
    }

    private void authorize(Authentication authentication, long advertiserId) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要已认证的管理员身份");
        }
        if (authentication.getAuthorities().stream().noneMatch(a -> ADMIN_AUTHORITY.equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "sandbox 预算仅允许管理员操作");
        }
        if (advertiserId <= 0) throw new IllegalArgumentException("advertiserId 须为正整数");
        if (!advertisers.advertiserExists(advertiserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "广告主不存在");
        }
    }

    /** 本接口的 JSON 解析失败保持 400，不能泄漏底层解析细节。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> invalidJson(HttpServletRequest request) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of("BAD_REQUEST", "预算命令 JSON 格式非法", request.getRequestURI(), null));
    }

    /** JsonNode 仅用于输入类型检查，禁止金额数字转文本、版本小数截断等宽松强制转换。 */
    public record LimitRequest(String commandId, String currency, JsonNode limitAmount, JsonNode expectedVersion) {}

    /** HTTP 金额和广告主 ID 使用字符串，防止浏览器浮点和大整数精度损失。 */
    public record AccountView(String trafficClass, String advertiserId, String budgetDate, String currency,
                              String limitAmount, String heldAmount, String chargedAmount, String availableAmount, long version) {
        /** 同一快照派生全部金额字段。 */
        public static AccountView from(BudgetAccount account) {
            return new AccountView(BudgetAccount.TRAFFIC_CLASS, Long.toString(account.advertiserId()),
                    account.budgetDate().toString(), CnyAmount.CURRENCY, account.limitAmount().toPlainString(),
                    account.heldAmount().toPlainString(), account.chargedAmount().toPlainString(),
                    account.availableAmount().toPlainString(), account.version());
        }
    }

    /** 明确返回首次结果状态与快照。 */
    public record CommandView(String commandId, BudgetCommandResult.Status status, AccountView account) {
        /** 不向 HTTP 暴露内部数据库实体。 */
        public static CommandView from(BudgetCommandResult result) {
            return new CommandView(result.commandId().toString(), result.status(), AccountView.from(result.account()));
        }
    }
}
