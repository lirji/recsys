package com.recsys.ad.billing;

import com.recsys.ad.AdProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.mybatis.spring.SqlSessionTemplate;

/** 独立启用 feed 准入，防旧 owner 在尚未具备可信频控时接新混排流量。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"recsys.ad.billing.sandbox-enabled","recsys.ad.billing.feed-candidates-enabled"},havingValue="true")
@Conditional(SandboxOwnerCondition.class)
public class FeedCandidateConfiguration {
    /** 仅使用广告自有数据库并校验扩展迁移。 */
    @Bean public FeedCandidateRepository feedCandidateRepository(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate sessions) {
        var repository=new FeedCandidateRepository(sessions); repository.verifySchema(); return repository;
    }
    /** 复制既有频控配置作为启动快照，在途候选不会混用一次刷新前后的字段。 */
    @Bean public FeedCandidateService feedCandidateService(SandboxPreparationService preparation,
            FeedCandidateRepository repository, AdProperties properties) {
        var config=properties.getFreq();
        return new FeedCandidateService(preparation,repository,config.isEnabled(),config.getPerAd(),config.getPerAdvertiser());
    }
}
