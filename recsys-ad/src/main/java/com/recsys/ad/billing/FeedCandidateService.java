package com.recsys.ad.billing;

import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import java.util.List;

/** feed 候选准入，和搜索准备共用 owner，但不对纯候选产生任何资金副作用。 */
public class FeedCandidateService {
    private final SandboxPreparationService preparation;
    private final FeedCandidateRepository repository;
    private final boolean enabled;
    private final int perAd;
    private final int perAdvertiser;
    public FeedCandidateService(SandboxPreparationService preparation, FeedCandidateRepository repository,
                                boolean enabled, int perAd, int perAdvertiser) {
        if (perAd < 1 || perAdvertiser < 1) throw new IllegalArgumentException("频控上限必须为正整数");
        this.preparation=preparation; this.repository=repository; this.enabled=enabled;
        this.perAd=perAd; this.perAdvertiser=perAdvertiser;
    }
    /** 存储不可用向 engine 传播失败，只能在冻结前选择自然降级，不能不限频放行。 */
    public List<Candidate> candidates(Query query) {
        var candidates=preparation.candidates(query);
        if (candidates.size()>SandboxDelivery.MAX_SLOTS) throw new IllegalStateException("owner 候选数越界");
        if (!enabled || candidates.isEmpty()) return candidates;
        var eligible=repository.eligible(query.userId(),candidates,perAd,perAdvertiser);
        return candidates.stream().filter(c->eligible.contains(c.ad().adId())).toList();
    }
}
