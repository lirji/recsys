package com.recsys.recengine.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 已交互过滤:取用户**正反馈过**的物品集合,供编排层从召回结果里剔除"已看过"的。
 *
 * <p>口径与 {@link com.recsys.recengine.coldstart.ColdStartDetector} / i2i·u2u·swing 召回一致——
 * 只算真实互动 {@code CLICK/LIKE/PLAY/RATING},**排除 IMPRESSION**:曝光是系统每次推荐都会写的,
 * 若把它当"已看"过滤,用户被推过的物品会被永久屏蔽,几次请求后召回池会被自己写的曝光掏空。
 *
 * <p>注意:本过滤只用于**在线编排**(生产路)。离线 {@code eval} 作业直连召回服务、自行按时间切分
 * 处理历史/测试正例,不能走这条过滤,否则会把测试期正例也滤掉、无法衡量召回质量。
 *
 * <p>失败开放(fail-open):查询异常返回空集,即"不过滤",宁可偶发重复也不让过滤拖垮推荐主链路。
 */
@Component
public class SeenItemsFilter {

    private static final Logger log = LoggerFactory.getLogger(SeenItemsFilter.class);

    private final JdbcTemplate jdbc;

    public SeenItemsFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 返回该用户正反馈过的物品 id 集合;异常时返回空集(不过滤)。 */
    public Set<Long> seenItems(long userId) {
        try {
            List<Long> ids = jdbc.queryForList(
                    "SELECT DISTINCT item_id FROM user_behavior WHERE user_id=? " +
                    "AND action IN ('CLICK','LIKE','PLAY','RATING')", Long.class, userId);
            return new HashSet<>(ids);
        } catch (Exception e) {
            log.warn("已看过滤查询失败,本次不过滤 user={}: {}", userId, e.getMessage());
            return Set.of();
        }
    }
}
