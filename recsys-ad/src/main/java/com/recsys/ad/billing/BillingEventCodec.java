package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ImpressionReceipt.CapturedEvent;
import com.recsys.common.ad.delivery.SandboxDelivery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** v1 消费边界：严格已知字段类型，允许新增字段，哈希只包含已知语义。 */
public class BillingEventCodec {
    public static final int MAX_BYTES=16384;
    private static final List<String> STRINGS=List.of("eventId","eventType","trafficClass","currency","priceUnit","pricingVersion",
            "operationId","qualificationId","receiptId","advertiserId","adId","creativeId","userId","budgetDate","amount","acceptedAt");
    private final ObjectMapper json;
    public BillingEventCodec(ObjectMapper json) {
        this.json=json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    public record Validated(CapturedEvent event,String canonical,String hash) {}

    /** 无效事件不返回默认金额，不吞错 ACK；不在异常中回显原 payload。 */
    public Validated decode(String key,String payload) {
        try {
            if (payload==null || payload.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw new IllegalArgumentException();
            JsonNode tree=json.readTree(payload);
            for (String field:STRINGS) if (!tree.path(field).isTextual()) throw new IllegalArgumentException();
            for (String field:List.of("schemaVersion","slot","accountVersion")) {
                if (!tree.path(field).isIntegralNumber() || !tree.path(field).canConvertToLong()) throw new IllegalArgumentException();
            }
            if (tree.path("schemaVersion").longValue()!=CapturedEvent.SCHEMA_VERSION) throw new IllegalArgumentException();
            CapturedEvent e=json.treeToValue(tree,CapturedEvent.class);
            if (!CapturedEvent.TYPE.equals(e.eventType()) || !BudgetAccount.TRAFFIC_CLASS.equals(e.trafficClass())
                    || !CnyAmount.CURRENCY.equals(e.currency()) || !CpmPrice.UNIT.equals(e.priceUnit()) || !CpmPrice.VERSION.equals(e.pricingVersion())
                    || e.slot()<1 || e.slot()>SandboxDelivery.MAX_SLOTS || e.accountVersion()<1 || e.acceptedAt()==null) throw new IllegalArgumentException();
            for (String id:List.of(e.eventId(),e.operationId(),e.qualificationId(),e.receiptId())) {
                if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            }
            for (String id:List.of(e.advertiserId(),e.adId(),e.userId())) positiveId(id,false);
            positiveId(e.creativeId(),true);
            if (!LocalDate.parse(e.budgetDate()).toString().equals(e.budgetDate())
                    || !CnyAmount.parse(e.amount()).toPlainString().equals(e.amount())
                    || !(e.advertiserId()+":"+e.budgetDate()).equals(key)) throw new IllegalArgumentException();
            String canonical=json.writeValueAsString(e);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return new Validated(e,canonical,hash);
        } catch (Exception ex) { throw new InvalidEvent(); }
    }
    private void positiveId(String value,boolean zero) {
        long id=Long.parseLong(value);
        if (id<(zero?0:1) || !Long.toString(id).equals(value)) throw new IllegalArgumentException();
    }
    /** 错版本、类型或绑定统一停止消费；具体原始记录由受控 topic/offset 排查。 */
    public static class InvalidEvent extends RuntimeException {
        public InvalidEvent() { super("账务事件版本、类型或绑定无效"); }
    }
}
