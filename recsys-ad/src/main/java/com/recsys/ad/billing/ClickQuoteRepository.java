package com.recsys.ad.billing;

import com.recsys.common.ad.BidType;
import com.recsys.common.ad.billing.*;
import com.recsys.ad.persistence.mapper.addb.ClickQuoteMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 点击报价唯一写入口；无预算副作用，冻结价格后由后续准备用例占用完整义务。 */
public class ClickQuoteRepository {
    public static final String MIGRATION = "db/ad-billing/V008__click_quote.sql";
    private final ClickQuoteMapper mapper;
    private final DataSource dataSource;
    /** 实际会话工厂提供Mapper和owner资源身份，查询超时保留在XML语句上。 */
    public ClickQuoteRepository(SqlSessionTemplate sessions) {
        this.mapper = sessions.getMapper(ClickQuoteMapper.class);
        this.dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
    }
    /** 后续资金事务必须绑定同一资源，不以连接URL相同代替同一个连接池。 */
    public javax.sql.DataSource dataSource() { return dataSource; }
    /** 只有最终选择后的服务端候选才能形成此命令；没有客户端自报金额入口。 */
    public record Draft(UUID quoteId, UUID operationId, String subject, long userId, long advertiserId,
                        long adId, long creativeId, long itemId, int slot, String title, ClickPrice price) {
        public Draft {
            new FrozenClickQuote(quoteId, operationId, subject, userId, advertiserId, adId, creativeId, itemId,
                    slot, title, price, Instant.EPOCH, Instant.EPOCH.plusSeconds(FrozenClickQuote.TTL_SECONDS));
        }
        @Override public String toString() { return "ClickQuoteDraft[quoteId=" + quoteId + "]"; }
    }
    /** 显式迁移才可启用，不在应用启动时自动改共享库。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes()))) {
            throw new IllegalStateException("点击报价V008缺失或摘要漂移");
        }
        mapper.verifyQuote();
    }
    /** 候选deadline只限制首次签发；同命令并发、丢响应和重启都恢复原金额及原期限。 */
    public Optional<FrozenClickQuote> freezeBefore(Draft d, Instant candidateDeadline) {
        Objects.requireNonNull(candidateDeadline);
        var basis = d.price().basis();
        mapper.freeze(new ClickQuoteMapper.Draft(d.quoteId(), d.operationId(), d.subject(), d.userId(), d.advertiserId(), d.adId(),
                d.creativeId(), d.itemId(), d.slot(), d.title(), d.price().bidType().name(), d.price().amount(),
                basis.threshold().toPlainString(), basis.billFactor().toPlainString(), basis.increment(), basis.floor(), basis.cap(),
                hash(d), java.sql.Timestamp.from(candidateDeadline)));
        var stored = find(d.quoteId(),d.subject());
        if (stored.isPresent()) {
            var q = stored.get();
            var original = new Draft(q.quoteId(),q.operationId(),q.subject(),q.userId(),q.advertiserId(),q.adId(),
                    q.creativeId(),q.itemId(),q.slot(),q.title(),q.price());
            if (!original.equals(d)) throw new FrozenClickQuote.Conflict();
            return stored;
        }
        // 同最终位置已存在另一报价属于冲突；不能因当前deadline也已过而掩盖身份重用。
        if (mapper.atPosition(d.operationId(), d.slot())) throw new FrozenClickQuote.Conflict();
        if (!mapper.beforeDeadline(java.sql.Timestamp.from(candidateDeadline))) {
            return Optional.empty();
        }
        throw new IllegalStateException("点击报价写入结果未确认");
    }
    /** 原主体验证在结果返回前完成；报价可查不表示其预算已准备或已收费。 */
    public Optional<FrozenClickQuote> find(UUID id,String subject) {
        Objects.requireNonNull(id); Reservation.validateSubject(subject);
        return mapper.find(id).stream().findFirst().map(row -> {
            if (!subject.equals(row.subject())) throw new Reservation.AccessDenied();
            var price = new ClickPrice(BidType.valueOf(row.bidType()), new GspPrice(new BigDecimal(row.threshold()),
                    new BigDecimal(row.billFactor()), row.increment(), row.floor(), row.cap()));
            var q = new FrozenClickQuote(id, row.operationId(), subject, row.userId(), row.advertiserId(), row.adId(), row.creativeId(),
                    row.itemId(), row.slot(), row.title(), price, row.issuedAt().toInstant(), row.expiresAt().toInstant());
            var draft = new Draft(q.quoteId(),q.operationId(),subject,q.userId(),q.advertiserId(),q.adId(),q.creativeId(),q.itemId(),q.slot(),q.title(),price);
            if (price.amount().compareTo(row.amount()) != 0 || !hash(draft).equals(row.payloadHash())
                    || !ClickPrice.UNIT.equals(row.priceUnit()) || !ClickPrice.VERSION.equals(row.pricingVersion())) {
                throw new IllegalStateException("点击报价金额或冻结依据不一致");
            }
            return q;
        });
    }
    private static String hash(Draft d) {
        var p = d.price().basis();
        // 长度前缀编码防止题名或主体含换行时出现分隔歧义；归一化金额与数学依据均进入摘要。
        try (var bytes = new java.io.ByteArrayOutputStream(); var out = new java.io.DataOutputStream(bytes)) {
            for (String value : List.of(d.quoteId().toString(),d.operationId().toString(),d.subject(),Long.toString(d.userId()),
                    Long.toString(d.advertiserId()),Long.toString(d.adId()),Long.toString(d.creativeId()),Long.toString(d.itemId()),
                    Integer.toString(d.slot()),d.title(),d.price().bidType().name(),"SANDBOX",CnyAmount.CURRENCY,ClickPrice.UNIT,ClickPrice.VERSION,
                    p.threshold().toPlainString(),p.billFactor().toPlainString(),p.increment().toPlainString(),p.floor().toPlainString(),
                    p.cap().toPlainString(),d.price().amount().toPlainString())) out.writeUTF(value);
            out.flush();
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("点击报价摘要不可用",ex); }
    }

    /** 签名资格使用同一规范摘要绑定整份报价，避免另一套编码遗漏字段。 */
    public static String fingerprint(FrozenClickQuote q) {
        return hash(new Draft(q.quoteId(),q.operationId(),q.subject(),q.userId(),q.advertiserId(),q.adId(),
                q.creativeId(),q.itemId(),q.slot(),q.title(),q.price()));
    }
}
