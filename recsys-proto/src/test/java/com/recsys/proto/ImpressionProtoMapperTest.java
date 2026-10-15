package com.recsys.proto;

import com.recsys.common.ad.billing.ImpressionReceipt.*;
import com.recsys.proto.impression.v1.VisibleReceiptResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** RPC 金额、原日与纳秒边界不经浮点或毫秒截断。 */
@Tag("money-chain")
class ImpressionProtoMapperTest {
    @Test void exactCommandAndAcceptedRejectedResultsRoundTrip() {
        var command=new Command(UUID.randomUUID(),"admin","opaque-proof",1000,5000,true);
        assertEquals(command,ImpressionProtoMapper.fromProto(ImpressionProtoMapper.toProto(command)));
        var at=Instant.parse("2026-10-15T00:00:00.123456789Z");
        var accepted=new Result(command.receiptId(),UUID.randomUUID(),Status.ACCEPTED,UUID.randomUUID(),command.receiptId(),
                "99999999999999.999999",LocalDate.of(2026,10,14),at,at);
        assertEquals(accepted,ImpressionProtoMapper.fromProto(ImpressionProtoMapper.toProto(accepted)));
        var rejected=new Result(command.receiptId(),UUID.randomUUID(),Status.EXPIRED,null,null,"0.000000",null,null,at);
        assertEquals(rejected,ImpressionProtoMapper.fromProto(ImpressionProtoMapper.toProto(rejected)));
        assertThrows(IllegalArgumentException.class,()->ImpressionProtoMapper.fromProto(ImpressionProtoMapper.toProto(accepted).toBuilder().setStatus("FUTURE").build()));
        assertThrows(IllegalArgumentException.class,()->ImpressionProtoMapper.fromProto(VisibleReceiptResult.getDefaultInstance()));
    }
}
