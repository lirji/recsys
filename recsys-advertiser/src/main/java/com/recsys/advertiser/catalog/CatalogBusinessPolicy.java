package com.recsys.advertiser.catalog;

import com.recsys.advertiser.CreativeReview;
import com.recsys.advertiser.dto.*;
import com.recsys.advertiser.persistence.mapper.primary.CatalogBusinessMapper.*;
import static com.recsys.advertiser.AdvertiserRules.*;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;

/** 可靠路径复用既有校验和机审；根状态由独立fanout协议控制，旧命令保留原语义。 */
public final class CatalogBusinessPolicy {
    private CatalogBusinessPolicy() { }

    /** 纯开户校验先于身份预留，非法请求不制造无法执行的持久占有。 */
    public static void requireNewRoot(AdvertiserUpsert input) {
        if(input==null || isBlank(input.name()))throw badRequest("name 必填");
        if(input.dailyBudget()==null || input.dailyBudget()<0)throw badRequest("dailyBudget 必填且非负");
        normalizeAdvertiserStatus(input.status(),"active");
    }

    /** 开户/根属性保留空值补丁语义；S16A不把状态变更降回legacy。 */
    public static Root root(long id,Root old,AdvertiserUpsert input) {
        if(input==null)throw badRequest("广告主参数必填");
        if(old==null) {
            requireNewRoot(input);
            return new Root(id,input.name(),input.dailyBudget(),normalizeAdvertiserStatus(input.status(),"active"));
        }
        if(input.dailyBudget()!=null && input.dailyBudget()<0)throw badRequest("dailyBudget 非负");
        String status=input.status()==null?old.status():normalizeAdvertiserStatus(input.status(),old.status());
        if(!status.equals(old.status()))throw failure(Failure.FANOUT_NOT_AVAILABLE);
        return new Root(id,or(input.name(),old.name()),or(input.dailyBudget(),old.dailyBudget()),old.status());
    }

    /** 状态和名称/预算补丁一起冻结；状态变化是否fanout由持久root步骤判断。 */
    public static Root rootStatus(long id,Root old,AdvertiserUpsert input) {
        if(old==null)throw failure(Failure.NOT_FOUND);
        if(input==null)throw badRequest("广告主参数必填");
        if(input.dailyBudget()!=null && input.dailyBudget()<0)throw badRequest("dailyBudget 非负");
        return new Root(id,or(input.name(),old.name()),or(input.dailyBudget(),old.dailyBudget()),normalizeAdvertiserStatus(input.status(),old.status()));
    }

    /** ad的期望最终状态先冻结；更新不擅自重新机审，只有显式送审重跑原规则。 */
    public static Ad ad(long owner,long id,Ad old,CatalogMutation mutation) {
        var kind=mutation.kind();var input=mutation.ad();
        if(kind==Kind.AD_CREATE) {
            if(input==null || input.itemId()==null)throw badRequest("itemId 必填");
            String opt=normalizeOptType(input.optimizationType());requireTargetCpa(opt,input.targetCpa());
            String title=or(input.title(),"");var review=CreativeReview.machineReview(title,input.landingUrl());
            return new Ad(id,owner,input.itemId(),title,input.landingUrl(),or(input.qualityScore(),1.0),
                    normalizeAdStatus(input.status(),"active"),review.status(),review.reason(),opt,input.targetCpa(),normalizeAudienceId(input.audienceId()));
        }
        if(old==null)throw failure(Failure.NOT_FOUND);
        if(kind==Kind.AD_UPDATE) {
            if(input==null)throw badRequest("广告参数必填");
            String opt=input.optimizationType()==null?old.optimizationType():normalizeOptType(input.optimizationType());
            Double cpa=or(input.targetCpa(),old.targetCpa());requireTargetCpa(opt,cpa);
            return new Ad(id,owner,or(input.itemId(),old.itemId()),or(input.title(),old.title()),or(input.landingUrl(),old.landingUrl()),
                    or(input.qualityScore(),old.qualityScore()),normalizeAdStatus(input.status(),old.status()),old.reviewStatus(),old.reviewReason(),
                    opt,cpa,input.audienceId()==null?old.audienceId():normalizeAudienceId(input.audienceId()));
        }
        String status=old.status(),review=old.reviewStatus(),reason=old.reviewReason();
        if(kind==Kind.AD_STATUS)status=normalizeAdStatus(mutation.status(),"active");
        if(kind==Kind.AD_REVIEW){review=CreativeReview.normalizeDecision(mutation.decision());reason=mutation.reason();}
        if(kind==Kind.AD_SUBMIT_REVIEW){var result=CreativeReview.machineReview(old.title(),old.landingUrl());review=result.status();reason=result.reason();}
        return new Ad(id,owner,old.itemId(),old.title(),old.landingUrl(),old.qualityScore(),status,review,reason,old.optimizationType(),old.targetCpa(),old.audienceId());
    }

    /** 新增关键词与局部更新保留原trim/大小写/模式规则；批量初始词先全部校验再写。 */
    public static BidwordView bidword(long ad,long id,BidwordView old,BidwordUpsert input) {
        if(input==null)throw badRequest("竞价词参数必填");
        if(old==null && (isBlank(input.keyword()) || input.bid()==null))throw badRequest("keyword和bid必填");
        if(input.bid()!=null && input.bid()<=0)throw badRequest("bid 为正");
        String keyword=input.keyword()==null?old.keyword():input.keyword().trim().toLowerCase();
        String match=input.matchType()==null && old!=null?old.matchType():normalizeMatchType(input.matchType());
        return new BidwordView(id,ad,keyword,match,or(input.bid(),old==null?null:old.bid()),
                or(input.bidMode(),old==null?"CPC":old.bidMode()));
    }

    /** 创意更新仅改原公开字段，不从旧DTO缺失reviewStatus推断审核通过。 */
    public static Creative creative(long ad,long id,Creative old,CreativeUpsert input) {
        if(input==null)throw badRequest("创意参数必填");
        if(old==null && isBlank(input.title()))throw badRequest("title 必填");
        return new Creative(id,ad,or(input.title(),old==null?null:old.title()),or(input.landingUrl(),old==null?null:old.landingUrl()),
                normalizeAdStatus(input.status(),old==null?"active":old.status()),old==null?null:old.reviewStatus());
    }
    private static <T>T or(T value,T fallback){return value==null?fallback:value;}
}
