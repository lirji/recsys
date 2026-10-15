package com.recsys.proto;

import com.google.protobuf.Timestamp;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.CnyAmount;
import com.recsys.common.ad.billing.CpmPrice;
import com.recsys.common.ad.billing.FrozenCpmQuote;
import com.recsys.proto.billing.v1.FrozenCpmQuoteReply;

import java.math.BigDecimal;
import java.time.Instant;

/** 冻结报价的精确传输映射；未知来源/版本/单位或金额不一致一律拒绝。 */
public final class CpmQuoteProtoMapper {
    private CpmQuoteProtoMapper() {}

    /** 保留数据库微秒时间，不使用 epochMillis 截断有效期限。 */
    public static FrozenCpmQuoteReply toProto(FrozenCpmQuote quote) {
        CpmPrice price = quote.price();
        return FrozenCpmQuoteReply.newBuilder().setQuoteId(quote.quoteId().toString()).setDeliveryId(quote.deliveryId().toString())
                .setAdvertiserId(quote.advertiserId()).setAdId(quote.adId()).setSlot(quote.slot()).setTrafficClass("SANDBOX")
                .setCurrency(CnyAmount.CURRENCY).setPriceUnit(CpmPrice.UNIT).setPricingVersion(CpmPrice.VERSION)
                .setAmount(price.amount().toPlainString()).setThreshold(price.threshold().toPlainString())
                .setBillFactor(price.billFactor().toPlainString()).setIncrementAmount(price.increment().toPlainString())
                .setFloorAmount(price.floor().toPlainString()).setCapAmount(price.cap().toPlainString())
                .setIssuedAt(timestamp(quote.issuedAt())).setExpiresAt(timestamp(quote.expiresAt())).build();
    }

    /** 反向转换只消费可信服务响应，不赋予终端传入价格授权。 */
    public static FrozenCpmQuote fromProto(FrozenCpmQuoteReply reply) {
        if (!"SANDBOX".equals(reply.getTrafficClass()) || !CnyAmount.CURRENCY.equals(reply.getCurrency())
                || !CpmPrice.UNIT.equals(reply.getPriceUnit()) || !CpmPrice.VERSION.equals(reply.getPricingVersion())
                || !reply.hasIssuedAt() || !reply.hasExpiresAt()) {
            throw new IllegalArgumentException("报价来源、单位、版本或时间不受支持");
        }
        CpmPrice price = new CpmPrice(decimal(reply.getThreshold()), decimal(reply.getBillFactor()),
                CnyAmount.parse(reply.getIncrementAmount()), CnyAmount.parse(reply.getFloorAmount()), CnyAmount.parse(reply.getCapAmount()));
        if (price.amount().compareTo(CnyAmount.parse(reply.getAmount())) != 0) {
            throw new IllegalArgumentException("报价金额与冻结依据不一致");
        }
        return new FrozenCpmQuote(BudgetCommand.parseId(reply.getQuoteId()), BudgetCommand.parseId(reply.getDeliveryId()),
                reply.getAdvertiserId(), reply.getAdId(), reply.getSlot(), price, instant(reply.getIssuedAt()), instant(reply.getExpiresAt()));
    }

    private static BigDecimal decimal(String value) {
        if (value.length() > 800 || !value.matches("[0-9]+(\\.[0-9]+)?")) {
            throw new IllegalArgumentException("报价依据须为有界普通十进制");
        }
        return new BigDecimal(value);
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build();
    }

    private static Instant instant(Timestamp value) {
        if (value.getNanos() < 0 || value.getNanos() > 999999999) throw new IllegalArgumentException("报价时间非法");
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    }
}
