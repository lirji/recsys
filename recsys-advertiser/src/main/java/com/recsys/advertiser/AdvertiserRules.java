package com.recsys.advertiser;

import org.springframework.http.HttpStatus;
import com.recsys.common.ad.BidType;
import org.springframework.web.server.ResponseStatusException;

/** 管理面既有字段规则共享给legacy与可靠写口，避免两种模式接受不同业务输入。 */
public final class AdvertiserRules {
    private AdvertiserRules() { }
    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 管理面允许 active / paused;over_budget 由在线 pacing 自动置位,这里不接受。 */
    public static String normalizeAdvertiserStatus(String s, String def) {
        if (s == null) {
            return def;
        }
        String v = s.trim().toLowerCase();
        if (!v.equals("active") && !v.equals("paused")) {
            throw badRequest("status 仅支持 active / paused");
        }
        return v;
    }

    public static String normalizeAdStatus(String s, String def) {
        if (s == null) {
            return def;
        }
        String v = s.trim().toLowerCase();
        if (!v.equals("active") && !v.equals("paused")) {
            throw badRequest("status 仅支持 active / paused");
        }
        return v;
    }

    public static String normalizeOptType(String s) {
        if (s == null) {
            return "CPC";
        }
        String v = s.trim().toUpperCase();
        if (!v.equals("CPC") && !v.equals("OCPC") && !v.equals("CPM") && !v.equals("OCPM") && !v.equals("CPA")) {
            throw badRequest("optimizationType 仅支持 CPC / OCPC / CPM / OCPM / CPA");
        }
        return v;
    }

    public static void requireTargetCpa(String optType, Double targetCpa) {
        if ((BidType.OCPC.name().equals(optType) || BidType.OCPM.name().equals(optType) || BidType.CPA.name().equals(optType))
                && (targetCpa == null || targetCpa <= 0)) {
            throw badRequest("optimizationType=" + optType + " 时 targetCpa 必填且为正");
        }
    }

    /** 空/非正 → 不定向;正数才写入 audience_id。 */
    public static Long normalizeAudienceId(Long audienceId) {
        return audienceId == null || audienceId <= 0 ? null : audienceId;
    }

    public static String normalizeMatchType(String s) {
        if (s == null) {
            return "EXACT";
        }
        String v = s.trim().toUpperCase();
        if (!v.equals("EXACT") && !v.equals("PHRASE") && !v.equals("BROAD")) {
            throw badRequest("matchType 仅支持 EXACT / PHRASE / BROAD");
        }
        return v;
    }

    public static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

}
