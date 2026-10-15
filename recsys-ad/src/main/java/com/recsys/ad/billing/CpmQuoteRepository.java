package com.recsys.ad.billing;

import com.recsys.common.ad.billing.CpmPrice;
import com.recsys.common.ad.billing.FrozenCpmQuote;
import com.recsys.ad.persistence.mapper.addb.CpmQuoteMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;
import javax.sql.DataSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** 报价 owner 的不可变持久化入口；单条 INSERT 原子保存，冲突只读原快照、不刷新 TTL。 */
public class CpmQuoteRepository {
    public static final String MIGRATION = "db/ad-billing/V002__cpm_quote.sql";
    private final CpmQuoteMapper mapper;
    private final DataSource dataSource;

    /** 从实际会话工厂取得owner资源，供外层预占事务核验同源。 */
    public CpmQuoteRepository(SqlSessionTemplate sessions) {
        this.mapper = sessions.getMapper(CpmQuoteMapper.class);
        this.dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
    }

    /** 预占用例必须从同一 owner 数据源读取报价，不能拼接另一个库的事务。 */
    public javax.sql.DataSource dataSource() { return dataSource; }

    /** 新版本能力启用前核对 V002；V001 仍由预算装配独立校验。 */
    public void verifySchema() {
        String installed = DataAccessUtils.requiredSingleResult(mapper.migrationHashes());
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(installed)) {
            throw new IllegalStateException("adDb CPM 报价迁移缺失或脚本摘要不一致");
        }
        mapper.verifyQuote();
    }

    /** 仅服务端已选 CPM 候选可调用；quoteId 和 delivery/slot 任一重用都必须保持原语义。 */
    public FrozenCpmQuote freeze(UUID quoteId, UUID deliveryId, long advertiserId, long adId, int slot, CpmPrice price) {
        return freezeBefore(quoteId, deliveryId, advertiserId, adId, slot, price, Instant.parse("9999-01-01T00:00:00Z"))
                .orElseThrow(() -> new IllegalStateException("报价签发期限已过"));
    }

    /** 候选有效期只限制首次创建；已有同一报价仍返回原快照，不续期。 */
    public Optional<FrozenCpmQuote> freezeBefore(UUID quoteId, UUID deliveryId, long advertiserId, long adId,
                                                int slot, CpmPrice price, Instant deadline) {
        // 复用领域约束校验标识，实际签发/到期时间全部由下面数据库时钟生成。
        new FrozenCpmQuote(quoteId, deliveryId, advertiserId, adId, slot, price,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(FrozenCpmQuote.TTL_SECONDS));
        String hash = hash(quoteId, deliveryId, advertiserId, adId, slot, price);
        mapper.freeze(new CpmQuoteMapper.Draft(quoteId, deliveryId, advertiserId, adId, slot, price.amount(),
                price.threshold().toPlainString(), price.billFactor().toPlainString(), price.increment(), price.floor(), price.cap(),
                hash, java.sql.Timestamp.from(deadline)));
        var stored = mapper.hashes(quoteId);
        if (stored.isEmpty() && !mapper.beforeDeadline(java.sql.Timestamp.from(deadline))) return Optional.empty();
        if (stored.size() != 1 || !hash.equals(stored.getFirst())) throw new FrozenCpmQuote.Conflict();
        return Optional.of(find(quoteId).orElseThrow(() -> new IllegalStateException("已保存报价不可查询")));
    }

    /** 仅供 owner 内部用例；公开端点须先校验交付主体，不暴露无鉴权按 UUID 查询。 */
    public Optional<FrozenCpmQuote> find(UUID quoteId) {
        return mapper.quotes(quoteId).stream().findFirst().map(row -> {
            var price = new CpmPrice(new BigDecimal(row.threshold()), new BigDecimal(row.billFactor()),
                    row.increment(), row.floor(), row.cap());
            if (price.amount().compareTo(row.amount()) != 0) {
                throw new IllegalStateException("报价金额与冻结依据不一致");
            }
            return new FrozenCpmQuote(row.quoteId(), row.deliveryId(), row.advertiserId(), row.adId(), row.slot(), price,
                    row.issuedAt().toInstant(), row.expiresAt().toInstant());
        });
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
