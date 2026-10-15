package com.recsys.common.ad.billing;

import com.recsys.common.ad.BidType;
import java.math.BigDecimal;
import java.util.Objects;

/** 点击结算依据明确计费单位；不能传入CPM票据或从展示报价反推金额。 */
public record ClickPrice(BidType bidType, GspPrice basis) {
    public static final String VERSION = "CLICK_GSP_CNY6_V1";
    public static final String UNIT = "PER_CLICK";
    public ClickPrice {
        Objects.requireNonNull(basis);
        if (bidType != BidType.CPC && bidType != BidType.OCPC) throw new IllegalArgumentException("仅支持CPC/OCPC点击报价");
    }
    /** 最大收费义务为完整的一次点击金额，不乘点击概率折算占用。 */
    public BigDecimal amount() { return basis.amount(); }
}
