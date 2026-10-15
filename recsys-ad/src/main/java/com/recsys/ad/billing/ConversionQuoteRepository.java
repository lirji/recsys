package com.recsys.ad.billing;

import com.recsys.common.ad.BidType;
import com.recsys.common.ad.billing.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 转化报价唯一写入口；无预算副作用，冻结价格后由后续准备用例占用完整义务。 */
public class ConversionQuoteRepository {
    public static final String MIGRATION = "db/ad-billing/V012__conversion_quote.sql";
    private final JdbcTemplate jdbc;
    public ConversionQuoteRepository(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(5);
    }
    /** 后续资金事务必须绑定同一资源，不以连接URL相同代替同一个连接池。 */
    public javax.sql.DataSource dataSource() { return jdbc.getDataSource(); }
    /** 只有最终选择后的服务端候选才能形成此命令；没有客户端自报金额入口。 */
    public record Draft(UUID quoteId, UUID operationId, String subject, long userId, long advertiserId,
                        long adId, long creativeId, long itemId, int slot, String title, ConversionPrice price) {
        public Draft {
            new FrozenConversionQuote(quoteId, operationId, subject, userId, advertiserId, adId, creativeId, itemId,
                    slot, title, price, Instant.EPOCH, Instant.EPOCH.plusSeconds(FrozenConversionQuote.TTL_SECONDS));
        }
        @Override public String toString() { return "ConversionQuoteDraft[quoteId=" + quoteId + "]"; }
    }
    /** 显式迁移才可启用，不在应用启动时自动改共享库。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(jdbc.queryForObject(
                "SELECT script_sha256 FROM ad_billing_schema_history WHERE version=12", String.class))) {
            throw new IllegalStateException("转化报价V012缺失或摘要漂移");
        }
        jdbc.queryForList("SELECT quote_id,bid_type,payload_hash FROM ad_conversion_quote LIMIT 0");
    }
    /** 候选deadline只限制首次签发；同命令并发、丢响应和重启都恢复原金额及原期限。 */
    public Optional<FrozenConversionQuote> freezeBefore(Draft d, Instant candidateDeadline) {
        Objects.requireNonNull(candidateDeadline);
        var basis = d.price().basis();
        jdbc.update("""
                WITH issued AS (SELECT clock_timestamp() AS instant)
                INSERT INTO ad_conversion_quote(quote_id,operation_id,subject_key,user_id,advertiser_id,ad_id,creative_id,item_id,
                    slot,title,bid_type,amount,threshold,bill_factor,increment_amount,floor_amount,cap_amount,payload_hash,issued_at,expires_at)
                SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,instant,instant+interval '5 minutes'
                FROM issued WHERE instant<? ON CONFLICT DO NOTHING
                """, d.quoteId(),d.operationId(),d.subject(),d.userId(),d.advertiserId(),d.adId(),d.creativeId(),d.itemId(),
                d.slot(),d.title(),d.price().bidType().name(),d.price().amount(),basis.threshold().toPlainString(),
                basis.billFactor().toPlainString(),basis.increment(),basis.floor(),basis.cap(),hash(d),java.sql.Timestamp.from(candidateDeadline));
        var stored = find(d.quoteId(),d.subject());
        if (stored.isPresent()) {
            var q = stored.get();
            var original = new Draft(q.quoteId(),q.operationId(),q.subject(),q.userId(),q.advertiserId(),q.adId(),
                    q.creativeId(),q.itemId(),q.slot(),q.title(),q.price());
            if (!original.equals(d)) throw new FrozenConversionQuote.Conflict();
            return stored;
        }
        // 同最终位置已存在另一报价属于冲突；不能因当前deadline也已过而掩盖身份重用。
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ad_conversion_quote WHERE operation_id=? AND slot=?)",
                Boolean.class,d.operationId(),d.slot()))) throw new FrozenConversionQuote.Conflict();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT clock_timestamp()<?",Boolean.class,java.sql.Timestamp.from(candidateDeadline)))) {
            return Optional.empty();
        }
        throw new IllegalStateException("转化报价写入结果未确认");
    }
    /** 原主体验证在结果返回前完成；报价可查不表示其预算已准备或已收费。 */
    public Optional<FrozenConversionQuote> find(UUID id,String subject) {
        Objects.requireNonNull(id); Reservation.validateSubject(subject);
        return jdbc.query("SELECT * FROM ad_conversion_quote WHERE quote_id=?",(rs,row)->{
            if (!subject.equals(rs.getString("subject_key"))) throw new Reservation.AccessDenied();
            var price = new ConversionPrice(new GspPrice(
                    new BigDecimal(rs.getString("threshold")),new BigDecimal(rs.getString("bill_factor")),
                    rs.getBigDecimal("increment_amount"),rs.getBigDecimal("floor_amount"),rs.getBigDecimal("cap_amount")));
            var q = new FrozenConversionQuote(id,rs.getObject("operation_id",UUID.class),subject,rs.getLong("user_id"),
                    rs.getLong("advertiser_id"),rs.getLong("ad_id"),rs.getLong("creative_id"),rs.getLong("item_id"),
                    rs.getInt("slot"),rs.getString("title"),price,rs.getObject("issued_at",OffsetDateTime.class).toInstant(),
                    rs.getObject("expires_at",OffsetDateTime.class).toInstant());
            var draft = new Draft(q.quoteId(),q.operationId(),subject,q.userId(),q.advertiserId(),q.adId(),q.creativeId(),q.itemId(),q.slot(),q.title(),price);
            if (!BidType.CPA.name().equals(rs.getString("bid_type")) || price.amount().compareTo(rs.getBigDecimal("amount"))!=0 || !hash(draft).equals(rs.getString("payload_hash"))
                    || !ConversionPrice.UNIT.equals(rs.getString("price_unit")) || !ConversionPrice.VERSION.equals(rs.getString("pricing_version"))) {
                throw new IllegalStateException("转化报价金额或冻结依据不一致");
            }
            return q;
        },id).stream().findFirst();
    }
    private static String hash(Draft d) {
        var p = d.price().basis();
        // 长度前缀编码防止题名或主体含换行时出现分隔歧义；归一化金额与数学依据均进入摘要。
        try (var bytes = new java.io.ByteArrayOutputStream(); var out = new java.io.DataOutputStream(bytes)) {
            for (String value : List.of(d.quoteId().toString(),d.operationId().toString(),d.subject(),Long.toString(d.userId()),
                    Long.toString(d.advertiserId()),Long.toString(d.adId()),Long.toString(d.creativeId()),Long.toString(d.itemId()),
                    Integer.toString(d.slot()),d.title(),d.price().bidType().name(),"SANDBOX",CnyAmount.CURRENCY,ConversionPrice.UNIT,ConversionPrice.VERSION,
                    p.threshold().toPlainString(),p.billFactor().toPlainString(),p.increment().toPlainString(),p.floor().toPlainString(),
                    p.cap().toPlainString(),d.price().amount().toPlainString())) out.writeUTF(value);
            out.flush();
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("转化报价摘要不可用",ex); }
    }

    /** 签名资格使用同一规范摘要绑定整份报价，避免另一套编码遗漏字段。 */
    public static String fingerprint(FrozenConversionQuote q) {
        return hash(new Draft(q.quoteId(),q.operationId(),q.subject(),q.userId(),q.advertiserId(),q.adId(),
                q.creativeId(),q.itemId(),q.slot(),q.title(),q.price()));
    }
}
