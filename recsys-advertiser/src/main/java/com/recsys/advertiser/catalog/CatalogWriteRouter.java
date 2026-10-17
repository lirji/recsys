package com.recsys.advertiser.catalog;

import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.advertiser.catalog.CatalogFragments.Request;
import com.recsys.advertiser.persistence.mapper.primary.CatalogBusinessMapper;
import com.recsys.common.error.ErrorCode;
import com.recsys.platform.web.PublicHttpException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;

/** 四个真实Controller的可靠分支；默认无此bean时仍走原legacy服务，启用后绝不降回旧双写。 */
public final class CatalogWriteRouter {
    public static final String COMMAND_HEADER="X-Catalog-Command-Id",OWNER_HEADER="X-Catalog-Advertiser-Id";
    private final UUID source;
    private final boolean admission;
    private final CatalogCommandRepository commands;
    private final CatalogCommandService service;
    private final CatalogBusinessMapper business;
    private final CatalogJson json;
    private final AdvertiserAuthz authz;
    public CatalogWriteRouter(UUID source,boolean admission,CatalogCommandRepository commands,CatalogCommandService service,
                               CatalogBusinessMapper business,CatalogJson json,AdvertiserAuthz authz) {
        this.source=source;this.admission=admission;this.commands=commands;this.service=service;this.business=business;this.json=json;this.authz=authz;
    }

    /** 原command查询优先于当前资源存在性；删除后相同稳定请求仍返回原响应并重新判权。 */
    public <T>T execute(CatalogMutation mutation,Long ownerFromPath,Class<T> resultType) {
        try {return executeAuthorized(mutation,ownerFromPath,resultType);}
        catch(CatalogException e){throw publicFailure(e.failure());}
        catch(org.springframework.dao.DataAccessException dependency){throw publicFailure(Failure.RESULT_UNKNOWN);}
    }
    private <T>T executeAuthorized(CatalogMutation mutation,Long ownerFromPath,Class<T> resultType) {
        var web=(ServletRequestAttributes)RequestContextHolder.currentRequestAttributes();
        String commandHeader=web.getRequest().getHeader(COMMAND_HEADER),ownerHeader=web.getRequest().getHeader(OWNER_HEADER);
        boolean root=mutation.kind()==Kind.ROOT_CREATE;
        if(root && ownerHeader!=null || !root && (commandHeader==null)!=(ownerHeader==null))throw badInput();
        UUID command;Long owner;
        try {command=commandHeader==null?UUID.randomUUID():UUID.fromString(commandHeader);owner=ownerHeader==null?ownerFromPath:Long.valueOf(ownerHeader);}
        catch(IllegalArgumentException e){throw badInput();}
        if(owner!=null && owner<=0 || ownerFromPath!=null && !Objects.equals(ownerFromPath,owner))throw badInput();
        if(web.getResponse()!=null)web.getResponse().setHeader(COMMAND_HEADER,command.toString());
        String actor=authz.catalogActor();Long ad=null;
        if(root)authz.requirePlatform("administrate");
        else if(owner==null) {
            ad=resolveAd(mutation);owner=single(business.ownersOfAd(ad));
        }
        if(!root)authorize(mutation.kind(),owner);
        CommandKey key=root?CommandKey.root(source,command):CommandKey.advertiser(source,owner,command);
        var original=commands.find(key);
        if(original!=null) {
            Request saved=json.decode(original.requestBytes(),Request.class);
            if(!json.tree(saved.mutation()).equals(json.tree(mutation)) || root && !original.actor().equals(actor))throw publicFailure(Failure.CONFLICT);
        } else {
            if(!admission)throw publicFailure(Failure.NOT_READY);
            if(root)CatalogBusinessPolicy.requireNewRoot(mutation.advertiser());
            if(mutation.kind()==Kind.AD_CREATE && mutation.ad()!=null && mutation.ad().bidwords()!=null
                    && mutation.ad().bidwords().size()>1024)throw publicFailure(Failure.SNAPSHOT_LIMIT);
            if(!root && mutation.kind()!=Kind.AD_CREATE && mutation.kind()!=Kind.ROOT_UPDATE) {
                ad=ad==null?resolveAd(mutation):ad;
                if(single(business.ownersOfAd(ad))!=owner)throw publicFailure(Failure.CONFLICT);
            }
            var request=new Request(mutation,ad);
            // HTTP原DTO/操作不变；仅新含status请求选择完整fanout协议，已保存A命令仍按原kind查询恢复。
            var kind=mutation.kind()==Kind.ROOT_UPDATE && mutation.advertiser()!=null && mutation.advertiser().status()!=null?Kind.ROOT_STATUS:mutation.kind();
            var submission=new Submission(key,kind,json.encode(request),actor,"HTTP_"+mutation.kind());
            original=root?commands.admitRoot(submission):commands.admit(submission);
        }
        if(web.getResponse()!=null && original.advertiserId()!=null)web.getResponse().setHeader(OWNER_HEADER,original.advertiserId().toString());
        try {
            if(State.REJECTED.name().equals(original.state()))throw failure(Failure.valueOf(original.errorType()));
            var result=original.completed()?original:service.advanceHttp(key);
            if(result==null || !result.completed())throw failure(Failure.RESULT_UNKNOWN);
            return json.decode(result.resultBytes(),resultType);
        } catch(CatalogException error){throw publicFailure(error.failure());}
        catch(ResponseStatusException error){throw error;}
        catch(RuntimeException unknown){throw publicFailure(Failure.RESULT_UNKNOWN);}
    }

    /** 查询只返回安全摘要，由Controller输出；不存在和跨owner不可探测。 */
    public CommandRow query(Long owner,UUID command) {
        if(owner!=null && owner<=0)throw badInput();
        String actor=authz.catalogActor();
        if(owner==null)authz.requirePlatform("administrate");else authz.requireAdvertiser("view",owner);
        try {
            var row=commands.find(owner==null?CommandKey.root(source,command):CommandKey.advertiser(source,owner,command));
            if(row==null || owner==null && !row.actor().equals(actor))throw new PublicHttpException(ErrorCode.NOT_FOUND);
            return row;
        } catch(org.springframework.dao.DataAccessException dependency){throw publicFailure(Failure.RESULT_UNKNOWN);}
    }
    private void authorize(Kind kind,long owner) {
        if(kind==Kind.AD_REVIEW)authz.requirePlatform("review");
        else authz.requireAdvertiser(kind==Kind.ROOT_UPDATE?"manage":"edit",owner);
    }
    private long resolveAd(CatalogMutation m) {
        return switch(m.kind()) {
            case BIDWORD_UPDATE,BIDWORD_DELETE -> single(business.adsOfBidword(m.resourceId()));
            case CREATIVE_UPDATE,CREATIVE_DELETE -> single(business.adsOfCreative(m.resourceId()));
            default -> m.resourceId();
        };
    }
    private static long single(List<Long> values) {if(values.isEmpty())throw new PublicHttpException(ErrorCode.NOT_FOUND);if(values.size()!=1)throw publicFailure(Failure.CONFLICT);return values.getFirst();}
    private static ResponseStatusException badInput(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"稳定目录命令头与广告主作用域不匹配");}
    public static PublicHttpException publicFailure(Failure failure) {
        return new PublicHttpException(switch(failure) {
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case INVALID_INPUT -> ErrorCode.BAD_REQUEST;
            case CONFLICT,OWNER_BUSY,FENCED -> ErrorCode.CATALOG_CONFLICT;
            case NOT_READY -> ErrorCode.CATALOG_NOT_READY;
            case PAUSED -> ErrorCode.CATALOG_PAUSED;
            case INCONSISTENT -> ErrorCode.CATALOG_INCONSISTENT;
            case SNAPSHOT_LIMIT -> ErrorCode.CATALOG_SNAPSHOT_LIMIT;
            case FANOUT_NOT_AVAILABLE -> ErrorCode.CATALOG_FANOUT_NOT_AVAILABLE;
            default -> ErrorCode.CATALOG_RESULT_UNKNOWN;
        });
    }
}
