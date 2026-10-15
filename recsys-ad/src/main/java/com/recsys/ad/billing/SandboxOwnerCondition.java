package com.recsys.ad.billing;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** grpc engine 只能通过 owner RPC 写钱库，避免本地重复启动账务与回收器。 */
public final class SandboxOwnerCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        return "recsys-ad-serving".equals(env.getProperty("spring.application.name"))
                || !"grpc".equals(env.getProperty("recsys.ad.serving.mode", "in-process"));
    }
}
