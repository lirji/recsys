package com.recsys.proto;

import com.recsys.common.ad.billing.*;
import com.recsys.proto.billing.v1.FrozenCpmQuoteReply;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** protobuf 序列化保持金额、依据、单位与数据库微秒期限完整一致。 */
@Tag("money-chain")
class CpmQuoteProtoMapperTest {
    @Test
    void preciseQuoteRoundTripAndRejectsUnknownVersionOrModifiedAmount() throws Exception {
        var price = new CpmPrice(new BigDecimal("0.0000005"), BigDecimal.ONE,
                CnyAmount.parse("0"), CnyAmount.parse("0"), CnyAmount.parse("1"));
        Instant issued = Instant.parse("2026-10-15T00:00:00.123456Z");
        var quote = new FrozenCpmQuote(UUID.randomUUID(), UUID.randomUUID(), Long.MAX_VALUE, 2, 1,
                price, issued, issued.plusSeconds(300));
        var wire = FrozenCpmQuoteReply.parseFrom(CpmQuoteProtoMapper.toProto(quote).toByteArray());
        assertEquals(quote, CpmQuoteProtoMapper.fromProto(wire));
        assertEquals("0.000001", wire.getAmount());
        assertThrows(IllegalArgumentException.class, () -> CpmQuoteProtoMapper.fromProto(wire.toBuilder().setPricingVersion("future").build()));
        assertThrows(IllegalArgumentException.class, () -> CpmQuoteProtoMapper.fromProto(wire.toBuilder().setAmount("1").build()));
        assertThrows(IllegalArgumentException.class, () -> CpmQuoteProtoMapper.fromProto(wire.toBuilder().setPriceUnit("PER_THOUSAND").build()));
        assertThrows(IllegalArgumentException.class, () -> CpmQuoteProtoMapper.fromProto(wire.toBuilder()
                .setExpiresAt(wire.getExpiresAt().toBuilder().setSeconds(wire.getExpiresAt().getSeconds() + 1)).build()));
    }
}
