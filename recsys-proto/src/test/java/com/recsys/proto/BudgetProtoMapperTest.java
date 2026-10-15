package com.recsys.proto;

import com.recsys.common.ad.billing.*;
import com.recsys.proto.billing.v1.BudgetCommandReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 protobuf 编解码的精确字段、状态兼容性验证。 */
@Tag("money-chain")
class BudgetProtoMapperTest {
    @Test
    void maximumMoneyAndLongIdsRoundTripExactly() throws Exception {
        var command = BudgetCommand.parse(Long.MAX_VALUE, UUID.randomUUID().toString(), "2026-10-15",
                "CNY", "99999999999999.999999", 0, "admin");
        assertEquals(command, BudgetProtoMapper.fromProto(BudgetProtoMapper.toProto(command)));
        var account = new BudgetAccount(command.advertiserId(), command.budgetDate(), command.limitAmount(),
                new BigDecimal("0.000001"), new BigDecimal("0.000002"), 1);
        for (var status : BudgetCommandResult.Status.values()) {
            var result = new BudgetCommandResult(command.commandId(), status, account);
            var wire = BudgetCommandReply.parseFrom(BudgetProtoMapper.toProto(result).toByteArray());
            assertEquals(result, BudgetProtoMapper.fromProto(wire));
            assertEquals("99999999999999.999996", wire.getAccount().getAvailableAmount());
        }
    }

    @Test
    void unknownStatusAndAccountOriginFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> BudgetProtoMapper.fromProto(BudgetCommandReply.getDefaultInstance()));
        assertThrows(IllegalArgumentException.class, () -> BudgetProtoMapper.fromProto(
                BudgetCommandReply.newBuilder().setStatusValue(99).build()));
        assertThrows(IllegalArgumentException.class, () -> BudgetProtoMapper.fromProto(
                com.recsys.proto.billing.v1.BudgetAccountReply.newBuilder().setTrafficClass("PRODUCTION").build()));
    }
}
