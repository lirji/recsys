package com.recsys.advertiser;

import java.util.List;
import java.util.Locale;

/**
 * 创意机审(A2)——纯函数,对创意标题/落地页做基础合规检查,产出审核决定。
 *
 * <p>状态机:新建广告 → 机审。命中违禁词 / 明显不合规 → 直接 {@code rejected}(不可服务);
 * 否则进 {@code pending_review} 等人审(可服务集只含 {@code approved},故待审广告默认不投放)。
 * 这是教学级机审(违禁词表 + 长度/占位检查);生产可换成模型打分(复用文本 embedding 做相似度/分类)。
 */
public final class CreativeReview {

    /** 审核决定 + 意见。 */
    public record Decision(String status, String reason) {
    }

    public static final String APPROVED = "approved";
    public static final String PENDING = "pending_review";
    public static final String REJECTED = "rejected";

    // 教学用违禁/敏感词(小写);命中即拒审。生产从配置/词库加载。
    private static final List<String> BANNED = List.of(
            "赌博", "博彩", "色情", "毒品", "枪支", "代开发票", "外挂",
            "gambling", "porn", "casino", "counterfeit", "weapon");

    private CreativeReview() {
    }

    /** 对标题 + 落地页机审。命中违禁 → rejected;空/过短标题 → rejected;否则 → pending_review 待人审。 */
    public static Decision machineReview(String title, String landingUrl) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || t.length() < 2) {
            return new Decision(REJECTED, "标题为空或过短");
        }
        String hay = (t + " " + (landingUrl == null ? "" : landingUrl)).toLowerCase(Locale.ROOT);
        for (String w : BANNED) {
            if (hay.contains(w)) {
                return new Decision(REJECTED, "命中违禁词:" + w);
            }
        }
        return new Decision(PENDING, "机审通过,待人工复核");
    }

    /** 规范化人审决定入参:approve → approved、reject → rejected;其余原样(容错到 pending)。 */
    public static String normalizeDecision(String decision) {
        if (decision == null) {
            return PENDING;
        }
        return switch (decision.trim().toLowerCase(Locale.ROOT)) {
            case "approve", "approved", "pass" -> APPROVED;
            case "reject", "rejected", "deny" -> REJECTED;
            case "pending", "pending_review" -> PENDING;
            default -> PENDING;
        };
    }
}
