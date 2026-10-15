package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.*;
import com.recsys.common.ad.BidType;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ClickSettlement.CapturedEvent;
import com.recsys.common.ad.delivery.SandboxDelivery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** v1 消费边界：严格已知字段类型，允许新增字段，哈希只包含已知语义。 */
public class ClickEventCodec {
    public static final int MAX_BYTES=16384;
    private static final List<String> STRINGS=List.of("eventId","eventType","trafficClass","currency","bidType","priceUnit","pricingVersion",
            "operationId","qualificationId","visibleReceiptId","clickReceiptId","advertiserId","adId","creativeId","itemId","userId","budgetDate","amount","acceptedAt");
    private final ObjectMapper json;
    public ClickEventCodec(ObjectMapper json) {
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
            // 事件解码严格使用显式枚举，不使用会把未知类型默认成CPC的兼容解析器。
            BidType bidType=BidType.valueOf(e.bidType());
            if (!CapturedEvent.TYPE.equals(e.eventType()) || !BudgetAccount.TRAFFIC_CLASS.equals(e.trafficClass())
                    || !CnyAmount.CURRENCY.equals(e.currency()) || !ClickPrice.UNIT.equals(e.priceUnit()) || !ClickPrice.VERSION.equals(e.pricingVersion())
                    || !(bidType==BidType.CPC || bidType==BidType.OCPC) || e.visibleReceiptId().equals(e.clickReceiptId())
                    || e.slot()<1 || e.slot()>SandboxDelivery.MAX_SLOTS || e.accountVersion()<1 || e.acceptedAt()==null) throw new IllegalArgumentException();
            for (String id:List.of(e.eventId(),e.operationId(),e.qualificationId(),e.visibleReceiptId(),e.clickReceiptId())) {
                if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            }
            for (String id:List.of(e.advertiserId(),e.adId(),e.itemId(),e.userId())) positiveId(id,false);
            positiveId(e.creativeId(),true);
            if (!LocalDate.parse(e.budgetDate()).toString().equals(e.budgetDate())
                    || !CnyAmount.parse(e.amount()).toPlainString().equals(e.amount())
                    || !(e.advertiserId()+":"+e.budgetDate()).equals(key)) throw new IllegalArgumentException();
            String canonical=json.writeValueAsString(e);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return new Validated(e,canonical,hash);
        } catch (Exception ex) { throw new BillingEventCodec.InvalidEvent(); }
    }
    private void positiveId(String value,boolean zero) {
        long id=Long.parseLong(value);
        if (id<(zero?0:1) || !Long.toString(id).equals(value)) throw new IllegalArgumentException();
    }
    /** 发送前再核验冻结逻辑目的地与账本ID，不用错误业务族的producer发送。 */
    public void validatePending(BillingOutboxRepository.Pending pending) {
        var value=decode(pending.key(),pending.payload());
        if(!CapturedEvent.TOPIC.equals(pending.destination()) || !value.event().eventId().equals(pending.eventId().toString())) {
            throw new BillingEventCodec.InvalidEvent();
        }
    }
}
