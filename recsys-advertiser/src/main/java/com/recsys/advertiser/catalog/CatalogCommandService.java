package com.recsys.advertiser.catalog;

import com.lrj.authz.protocol.AuthzEngine;
import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.advertiser.catalog.CatalogFragments.*;
import org.springframework.web.server.ResponseStatusException;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;

/** 跨物理库协调只推进一个不可变步骤；每个本地结果核实后才写owner检查点。 */
public final class CatalogCommandService {
    private final CatalogCommandRepository commands;
    private final CatalogBusinessSteps steps;
    private final CatalogEnrichment enrichment;
    private final CatalogJson json;
    private final AdvertiserAuthz authz;
    private final AuthzEngine relationshipClient;
    public CatalogCommandService(CatalogCommandRepository commands,CatalogBusinessSteps steps,CatalogEnrichment enrichment,
                                  CatalogJson json,AdvertiserAuthz authz,AuthzEngine relationshipClient) {
        this.commands=commands;this.steps=steps;this.enrichment=enrichment;this.json=json;this.authz=authz;this.relationshipClient=relationshipClient;
    }
    /** 每次仅领取一次原phase，不递归重试；领取失败/PAUSED由查询如实反映。 */
    public CommandRow advance(CommandKey key) {
        var current=commands.find(key);if(current==null)throw failure(Failure.NOT_FOUND);
        if(current.completed() || State.REJECTED.name().equals(current.state()) || State.PAUSED.name().equals(current.state()))return current;
        var claim=commands.claim(key);if(claim==null)return commands.find(key);
        var input=json.decode(claim.requestBytes(),Request.class);var kind=input.mutation().kind();
        try {
            switch(claim.phase()) {
                case "PREPARE" -> {
                    if(kind==Kind.ROOT_CREATE)commands.acquireRootOwner(claim);
                    // 已提交本地receipt先于当前资源重读，内容owner后续变化不能改写原步骤结果。
                    if(!steps.ownerApplied(claim))steps.validate(input,claim.advertiserId());
                    var owner=steps.owner(claim,input);
                    String next=kind==Kind.ROOT_CREATE?"AUTHZ":kind==Kind.ROOT_UPDATE?"ENRICH":"CHILDREN";
                    commands.advance(claim,next,next,json.encode(new Frozen(owner,null,null,null)));
                }
                case "CHILDREN" -> {
                    var frozen=json.decode(claim.frozenBytes(),Frozen.class);
                    var children=steps.children(claim,input,frozen.owner());
                    String next=kind==Kind.AD_DELETE?"FINAL":"ENRICH";
                    commands.advance(claim,next,next,json.encode(new Frozen(frozen.owner(),children,null,null)));
                }
                case "AUTHZ" -> {
                    if(CatalogCallBudget.remainingMillis()<4000)throw failure(Failure.RESULT_UNKNOWN);
                    authz.onCatalogAdvertiserCreated(claim.advertiserId(),claim.actor(),relationshipClient);
                    commands.advance(claim,"ENRICH","ENRICH",claim.frozenBytes());
                }
                case "ENRICH" -> {
                    var frozen=json.decode(claim.frozenBytes(),Frozen.class);
                    var result=frozen.owner().desired()==null?
                            new Frozen(frozen.owner(),null,null,enrichment.spent(claim.advertiserId())):
                            new Frozen(frozen.owner(),frozen.children(),enrichment.read(frozen.owner().desired().itemId()),null);
                    commands.advance(claim,"FINAL","FINAL",json.encode(result));
                }
                case "FINAL" -> {
                    var frozen=json.decode(claim.frozenBytes(),Frozen.class);
                    byte[] response=steps.finish(claim,input,frozen);
                    if(kind==Kind.ROOT_CREATE)commands.finishRoot(claim,response);
                }
                default -> throw failure(Failure.INCONSISTENT);
            }
        } catch(RuntimeException error) {
            var observed=commands.find(key);
            if(observed!=null && observed.completed())return observed;
            boolean invalid=error instanceof ResponseStatusException h && h.getStatusCode().value()==400
                    || error instanceof CatalogException c && (c.failure()==Failure.NOT_FOUND || c.failure()==Failure.FANOUT_NOT_AVAILABLE);
            if(invalid && PHASE_PREPARE.equals(claim.phase()) && key.scopeKind()==ScopeKind.ADVERTISER && !steps.ownerApplied(claim)) {
                commands.rejectUnstarted(claim,error instanceof CatalogException c?c.failure():Failure.INVALID_INPUT);throw error;
            }
            // 提交未知保持原scope/step/输入；下次先读APPLIED，不能换命令或释放占有。
            commands.failed(claim,error instanceof CatalogException c?c.failure():Failure.RESULT_UNKNOWN);
            throw error;
        }
        return commands.find(key);
    }
    /** HTTP推进最多四步且五秒截止；仍未完成时必须返回稳定命令身份供查询/续办。 */
    public CommandRow advanceHttp(CommandKey key) {
        CatalogCallBudget.begin();
        try {
            CommandRow current=commands.find(key);
            for(int step=0;step<4 && current!=null && !current.completed();step++) {
                CatalogCallBudget.remainingMillis();current=advance(key);
                if(State.PAUSED.name().equals(current.state()) || State.REJECTED.name().equals(current.state()))break;
            }
            return current;
        } finally {
            CatalogCallBudget.end();
        }
    }
}
