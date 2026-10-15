package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.SponsoredAd;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import com.recsys.ad.persistence.mapper.addb.UpliftAssignmentMapper;
import com.recsys.ad.persistence.model.UpliftAssignmentRow;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A7 随机采样器：对最终竞价页的末位非 GD 广告做 user-level show/no-show。
 * assignment 同步落库成功后才允许 control 删除；写失败 fail-open 展示且不形成样本。
 */
@Service
public class UpliftAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(UpliftAssignmentService.class);
    private final UpliftAssignmentMapper mapper;
    private final DataSource dataSource;
    private final AdProperties props;

    /** SQL和DDL复用广告自有源，采样开关关闭时不建立数据库连接。 */
    public UpliftAssignmentService(UpliftAssignmentMapper mapper, @Qualifier("adDbDataSource") DataSource dataSource,
                                   AdProperties props) {
        this.mapper = mapper; this.dataSource = dataSource; this.props = props;
    }

    @PostConstruct
    void ensureTables() {
        // 关闭采样时不要触碰广告库；表结构由 19_ad_uplift.sql/基础 schema 负责。
        if (!props.getUplift().isCollectionEnabled()) return;
        try {
            new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/ad-uplift-assignment.sql")).execute(dataSource);
        } catch (Exception e) {
            log.warn("uplift assignment 表初始化失败,采样将 fail-open: {}", e.getMessage());
        }
    }

    /** 仅持久化成功的控制组移除末位；冲突、窗口抑制和异常继续展示原广告。 */
    public Result collect(String requestId, long userId, List<SponsoredAd> ads,
                          List<AdCandidate> candidates, Map<Long, Double> pcvrByItem,
                          Map<Long, UpliftEstimate> estimates, String adBucket) {
        AdProperties.Uplift cfg = props.getUplift();
        if (!cfg.isCollectionEnabled() || ads == null || ads.isEmpty()) return new Result(ads, false, false);
        double treatmentPropensity = 1.0 - cfg.getControlRate();
        if (!(treatmentPropensity > 0.0 && treatmentPropensity < 1.0)) {
            log.warn("uplift control-rate 非法 {},采样已跳过", cfg.getControlRate());
            return new Result(ads, false, false);
        }
        SponsoredAd target = ads.get(ads.size() - 1);
        AdCandidate candidate = candidates.stream().filter(c -> c.adId() == target.adId()).findFirst().orElse(null);
        if (candidate == null || target.bidwordId() == 0L) return new Result(ads, false, false); // GD 不采样
        boolean treatment = uniform(userId, cfg.getSalt()) < treatmentPropensity;
        Instant now = Instant.now();
        UpliftEstimate estimate = estimates == null ? null : estimates.get(target.adId());
        String version = estimate == null ? "baseline" : estimate.modelVersion();
        try {
            var row = new UpliftAssignmentRow(requestId + ":" + target.adId(), requestId, userId,
                    target.adId(), target.advertiserId(), target.itemId(), treatment, treatmentPropensity,
                    target.pctr(), pcvrByItem.getOrDefault(target.itemId(), 0.0), candidate.quality(),
                    target.relevance(), candidate.bid(), adBucket, version, Timestamp.from(now),
                    Timestamp.from(now.plus(cfg.getOutcomeHorizonDays(), ChronoUnit.DAYS)));
            int inserted = mapper.insertIfEligible(userId + ":" + target.advertiserId(), row);
            if (inserted != 1) return new Result(ads, false, false);
        } catch (Exception e) {
            log.warn("uplift assignment 落库失败,fail-open 展示 req={}: {}", requestId, e.getMessage());
            return new Result(ads, false, false);
        }
        if (treatment) return new Result(ads, true, true);
        List<SponsoredAd> control = new ArrayList<>(ads);
        control.remove(control.size() - 1); // 末位留空，不回填，形成真实 no-show control
        return new Result(List.copyOf(control), true, false);
    }

    static double uniform(long userId, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((salt + ":" + userId).getBytes(StandardCharsets.UTF_8));
            long positive = ByteBuffer.wrap(bytes).getLong() & Long.MAX_VALUE;
            return positive / (double) Long.MAX_VALUE;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record Result(List<SponsoredAd> ads, boolean assigned, boolean treatment) { }
}
