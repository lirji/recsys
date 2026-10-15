package com.recsys.proto;

import com.recsys.common.ad.billing.BudgetAccount;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.BudgetCommandResult;
import com.recsys.common.ad.billing.CnyAmount;
import com.recsys.proto.billing.v1.BudgetAccountReply;
import com.recsys.proto.billing.v1.BudgetCommandReply;
import com.recsys.proto.billing.v1.BudgetCommandStatus;
import com.recsys.proto.billing.v1.SetBudgetLimitRequest;

/** 账务协议防腐层：不允许 double 或未知枚举默认值穿透精确金额与结果语义。 */
public final class BudgetProtoMapper {
    private BudgetProtoMapper() {}

    /** 可信调用方通过后仍严格校验负载，拒绝非法金额及币种。 */
    public static BudgetCommand fromProto(SetBudgetLimitRequest request) {
        return BudgetCommand.parse(request.getAdvertiserId(), request.getCommandId(), request.getBudgetDate(),
                request.getCurrency(), request.getLimitAmount(), request.getExpectedVersion(), request.getActor());
    }

    /** 请求以六位十进制传输，不经过浮点。 */
    public static SetBudgetLimitRequest toProto(BudgetCommand command) {
        return SetBudgetLimitRequest.newBuilder().setAdvertiserId(command.advertiserId())
                .setCommandId(command.commandId().toString()).setBudgetDate(command.budgetDate().toString())
                .setCurrency(CnyAmount.CURRENCY).setLimitAmount(command.limitAmount().toPlainString())
                .setExpectedVersion(command.expectedVersion()).setActor(command.actor()).build();
    }

    /** 可用额度从同一账户快照派生。 */
    public static BudgetAccountReply toProto(BudgetAccount account) {
        return BudgetAccountReply.newBuilder().setTrafficClass(BudgetAccount.TRAFFIC_CLASS)
                .setAdvertiserId(account.advertiserId()).setBudgetDate(account.budgetDate().toString())
                .setCurrency(CnyAmount.CURRENCY).setLimitAmount(account.limitAmount().toPlainString())
                .setHeldAmount(account.heldAmount().toPlainString()).setChargedAmount(account.chargedAmount().toPlainString())
                .setAvailableAmount(account.availableAmount().toPlainString()).setVersion(account.version()).build();
    }

    /** 错误来源/币种不能冒充当前 sandbox 账户。 */
    public static BudgetAccount fromProto(BudgetAccountReply reply) {
        if (!BudgetAccount.TRAFFIC_CLASS.equals(reply.getTrafficClass()) || !CnyAmount.CURRENCY.equals(reply.getCurrency())) {
            throw new IllegalArgumentException("不支持的预算账户来源或币种");
        }
        var account = new BudgetAccount(reply.getAdvertiserId(), BudgetCommand.parseDate(reply.getBudgetDate()),
                CnyAmount.parse(reply.getLimitAmount()), CnyAmount.parse(reply.getHeldAmount()),
                CnyAmount.parse(reply.getChargedAmount()), reply.getVersion());
        if (account.availableAmount().compareTo(CnyAmount.parse(reply.getAvailableAmount())) != 0) {
            throw new IllegalArgumentException("预算响应可用额度不一致");
        }
        return account;
    }

    /** 状态显式映射，新增状态必须经过契约升级才能被旧调用方接受。 */
    public static BudgetCommandReply toProto(BudgetCommandResult result) {
        return BudgetCommandReply.newBuilder().setCommandId(result.commandId().toString())
                .setStatus(BudgetCommandStatus.valueOf(result.status().name())).setAccount(toProto(result.account())).build();
    }

    /** 未设置/未来枚举按协议异常处理，绝不能当成已应用。 */
    public static BudgetCommandResult fromProto(BudgetCommandReply reply) {
        var status = switch (reply.getStatus()) {
            case APPLIED -> BudgetCommandResult.Status.APPLIED;
            case VERSION_CONFLICT -> BudgetCommandResult.Status.VERSION_CONFLICT;
            case BELOW_COMMITTED -> BudgetCommandResult.Status.BELOW_COMMITTED;
            default -> throw new IllegalArgumentException("不支持的预算命令结果");
        };
        return new BudgetCommandResult(BudgetCommand.parseId(reply.getCommandId()), status, fromProto(reply.getAccount()));
    }
}
