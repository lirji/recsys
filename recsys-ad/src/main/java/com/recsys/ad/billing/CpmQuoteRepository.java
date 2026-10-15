package com.recsys.ad.billing;

import com.recsys.common.ad.billing.CpmPrice;
import com.recsys.common.ad.billing.FrozenCpmQuote;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** 报价 owner 的不可变持久化入口；单条 INSERT 原子保存，冲突只读原快照、不刷新 TTL。 */
public class CpmQuoteRepository {
    public static final String MIGRATION = "db/ad-billing/V002__cpm_quote.sql";
    private final JdbcTemplate jdbc;

    public CpmQuoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 预占用例必须从同一 owner 数据源读取报价，不能拼接另一个库的事务。 */
    public javax.sql.DataSource dataSource() { return jdbc.getDataSource(); }

    /** 新版本能力启用前核对 V002；V001 仍由预算装配独立校验。 */
    public void verifySchema() {
        String installed = jdbc.queryForObject(
                "SELECT script_sha256 FROM ad_billing_schema_history WHERE version = 2", String.class);
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(installed)) {
            throw new IllegalStateException("adDb CPM 报价迁移缺失或脚本摘要不一致");
        }
        jdbc.queryForList("SELECT quote_id, amount, payload_hash, issued_at, expires_at FROM ad_cpm_quote LIMIT 0");
    }

    /** 仅服务端已选 CPM 候选可调用；quoteId 和 delivery/slot 任一重用都必须保持原语义。 */
    public FrozenCpmQuote freeze(UUID quoteId, UUID deliveryId, long advertiserId, long adId, int slot, CpmPrice price) {
        // 复用领域约束校验标识，实际签发/到期时间全部由下面数据库时钟生成。
        new FrozenCpmQuote(quoteId, deliveryId, advertiserId, adId, slot, price,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(FrozenCpmQuote.TTL_SECONDS));
        String hash = hash(quoteId, deliveryId, advertiserId, adId, slot, price);
        jdbc.update("""
                WITH issued AS (SELECT clock_timestamp() AS instant)
                INSERT INTO ad_cpm_quote (traffic_class, quote_id, delivery_id, advertiser_id, ad_id, slot,
                    currency, price_unit, pricing_version, amount, threshold, bill_factor, increment_amount,
                    floor_amount, cap_amount, payload_hash, issued_at, expires_at)
                SELECT 'SANDBOX', ?, ?, ?, ?, ?, 'CNY', 'PER_IMPRESSION', 'CPM_GSP_CNY6_V1', ?, ?, ?, ?, ?, ?, ?,
                    instant, instant + interval '5 minutes' FROM issued
                ON CONFLICT DO NOTHING
                """, quoteId, deliveryId, advertiserId, adId, slot, price.amount(), price.threshold().toPlainString(),
                price.billFactor().toPlainString(), price.increment(), price.floor(), price.cap(), hash);
        var stored = jdbc.query("SELECT payload_hash FROM ad_cpm_quote WHERE traffic_class = 'SANDBOX' AND quote_id = ?",
                (rs, row) -> rs.getString(1), quoteId);
        if (stored.size() != 1 || !hash.equals(stored.getFirst())) throw new FrozenCpmQuote.Conflict();
        return find(quoteId).orElseThrow(() -> new IllegalStateException("已保存报价不可查询"));
    }

    /** 仅供 owner 内部用例；公开端点须先校验交付主体，不暴露无鉴权按 UUID 查询。 */
    public Optional<FrozenCpmQuote> find(UUID quoteId) {
        return jdbc.query("SELECT * FROM ad_cpm_quote WHERE traffic_class = 'SANDBOX' AND quote_id = ?", (rs, row) -> {
            var price = new CpmPrice(new BigDecimal(rs.getString("threshold")), new BigDecimal(rs.getString("bill_factor")),
                    rs.getBigDecimal("increment_amount"), rs.getBigDecimal("floor_amount"), rs.getBigDecimal("cap_amount"));
            if (price.amount().compareTo(rs.getBigDecimal("amount")) != 0) {
                throw new IllegalStateException("报价金额与冻结依据不一致");
            }
            return new FrozenCpmQuote(rs.getObject("quote_id", UUID.class), rs.getObject("delivery_id", UUID.class),
                    rs.getLong("advertiser_id"), rs.getLong("ad_id"), rs.getInt("slot"), price,
                    rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("expires_at", OffsetDateTime.class).toInstant());
        }, quoteId).stream().findFirst();
    }

    private static String hash(UUID quoteId, UUID deliveryId, long advertiserId, long adId, int slot, CpmPrice price) {
        String canonical = String.join("\n", "SANDBOX", quoteId.toString(), deliveryId.toString(), Long.toString(advertiserId),
                Long.toString(adId), Integer.toString(slot), "CNY", CpmPrice.UNIT, CpmPrice.VERSION,
                price.threshold().toPlainString(), price.billFactor().toPlainString(), price.increment().toPlainString(),
                price.floor().toPlainString(), price.cap().toPlainString(), price.amount().toPlainString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行时缺少 SHA-256", ex);
        }
    }
}
